import com.intellij.credentialStore.OneTimeString
import com.intellij.database.model.DasDataSource
import com.intellij.database.model.RawDataSource
import com.intellij.database.psi.DataSourceManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.impl.ExtensionPointImpl
import com.intellij.openapi.project.ProjectManager
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.QueryStringDecoder
import liveplugin.show
import org.jetbrains.ide.BuiltInServerManager
import org.jetbrains.ide.RestService
import org.jsoup.internal.StringUtil
import java.util.regex.Pattern

// depends-on-plugin com.intellij.database

val service = object : RestService() {
    override fun execute(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext
    ): String? {


        sendOk(request, context);
        val uri = request.uri()

        val queryStringDecoder = QueryStringDecoder(uri)

        val parameters = queryStringDecoder.parameters()

        val params = parameters["params"]?.get(0)
        if (StringUtil.isBlank(params)) {
            show("addDataSource Bad Request")
            return null
        }
        val paramMap = params!!.split(Pattern.compile("\\s"))[1].split("|").map {
            val arr = it.split(Pattern.compile("="), 2)
            arr[0] to arr[1]
        }.toMap()
        add(paramMap)
        return null
    }

    override fun getServiceName(): String {
        return "addDataSource"
    }

    override fun isMethodSupported(method: HttpMethod): Boolean {
        return method == HttpMethod.GET;
    }

    override fun isHostTrusted(request: FullHttpRequest, urlDecoder: QueryStringDecoder): Boolean {
        return true
    }
}

// 获取并打印当前端口
val port = BuiltInServerManager.getInstance().port
show("db plugin is running on port: $port")

val handlers: ExtensionPointImpl<Any> =
    (ApplicationManager.getApplication().extensionArea.getExtensionPoint<Any>("com.intellij.httpRequestHandler")) as ExtensionPointImpl<Any>

handlers.findExtensions(Any::class.java).forEach {
    if (it.javaClass.name.startsWith("Plugin\$")) {
        // show("unregisterExtension: " + it.javaClass.name)
        handlers.unregisterExtension(it)
    }
}
handlers.registerExtension(service)

fun add(paramMap: Map<String, String>) {
    val proj = ProjectManager.getInstance().openProjects[0]
    val dm = findLocalDataSourceManager(proj)
    val dbname = paramMap["name"]?.split("[")!![0].split("@")!![1]
    val user = paramMap["user"]!!
    val password = paramMap["password"]?.toCharArray()!!

    getDataSources(dm).forEach { ds ->
        val name = (ds as? DasDataSource)?.name ?: call(ds, "getName") as? String
        if (name == dbname) {
            val props = call(ds, "getConnectionProperties") as? java.util.Properties
            props?.setProperty("characterSetResults", "NULL")
            call(ds, "setUsername", user) ?: call(ds, "setUserName", user)
            setPass(ds, password)
            debugSummary("update", dm, ds)
            return
        }
    }

    val ds = createLocalDataSource(
        dbname,
        "com.mysql.cj.jdbc.Driver",
        "jdbc:mysql://${paramMap["host"]}:${paramMap["port"]}",
        user
    ) ?: run {
        show("addDataSource failed: LocalDataSource class not found")
        return
    }
    call(ds, "resolveDriver")
    setPass(ds, password)
    call(ds, "setAutoSynchronize", true) ?: call(ds, "setAutoSynchronize", java.lang.Boolean.TRUE)
    val schemaMapping = call(ds, "getSchemaMapping")
    val scope = parseTreePattern("*:*")
    if (schemaMapping != null && scope != null) {
        call(schemaMapping, "setIntrospectionScope", scope)
    }
    val props = call(ds, "getConnectionProperties") as? java.util.Properties
    props?.setProperty("characterSetResults", "NULL")
    addDataSourceToManagers(proj, dm, ds)
    // Best-effort persistence/refresh for newer builds.
    call(dm, "fireDataSourceUpdated", ds)
    callAccessible(dm, "saveDataSource", ds) ?: callAccessible(dm, "saveDataSource", call(ds, "getDataSource") ?: ds)
    performAutoSyncTask(proj, ds)
    debugSummary("add", dm, ds)
}


fun setPass(ds: Any, password: CharArray) {
    val loader = ds.javaClass.classLoader
    val credsClass = findClassWithLoader(loader, "com.intellij.database.access.DatabaseCredentials")
    val storageClass = findClassWithLoader(loader, "com.intellij.database.dataSource.LocalDataSource\$Storage")
    val persist = storageClass?.enumConstants?.firstOrNull { it.toString() == "PERSIST" }
    val creds = if (credsClass != null) callStatic(credsClass, "getInstance") else null
    val oneTime = OneTimeString(password)
    if (persist != null) {
        call(ds, "setPasswordStorage", persist)
    }
    if (creds != null) {
        if (ds is DasDataSource && hasMethod(creds, "setPassword", arrayOf(ds, oneTime))) {
            call(creds, "setPassword", ds, oneTime)
            return
        }
        val manager = call(creds, "getCredentialManager")
        if (manager != null && persist != null && hasMethod(manager, "setPassword", arrayOf(ds, oneTime, persist))) {
            call(manager, "setPassword", ds, oneTime, persist)
            return
        }
        if (manager != null && persist != null && hasMethod(manager, "setPassword", arrayOf(ds, oneTime, persist, ""))) {
            call(manager, "setPassword", ds, oneTime, persist, "")
            return
        }
    }
    // Fallback for older APIs that allow setting password directly.
    call(ds, "setPassword", String(password))
}

fun findLocalDataSourceManager(project: Any): DataSourceManager<*> {
    val managers = getManagers(project)
    val localClass = findClassWithLoader(managers.first().javaClass.classLoader, "com.intellij.database.dataSource.LocalDataSourceManager")
    if (localClass != null) {
        val localManager = managers.firstOrNull { localClass.isInstance(it) }
        if (localManager != null) return localManager
    }
    val localDsClass = findClassWithLoader(managers.first().javaClass.classLoader, "com.intellij.database.dataSource.LocalDataSource") as? Class<out DasDataSource>
    if (localDsClass != null) {
        managers.firstOrNull { it.isMyDataSource(localDsClass) }?.let { return it }
    }
    return managers.first()
}

fun getManagers(project: Any): List<DataSourceManager<*>> {
    return DataSourceManager.getManagers(project as com.intellij.openapi.project.Project)
}

fun getDataSources(manager: DataSourceManager<*>): List<Any> {
    return manager.dataSources.filterNotNull()
}

fun createLocalDataSource(name: String, driver: String, url: String, user: String): Any? {
    val cls = findClass(
        "com.intellij.database.dataSource.LocalDataSource"
    ) ?: return null
    callStatic(cls, "create", name, driver, url, user)?.let { return it }
    val ctor = cls.constructors.firstOrNull { it.parameterTypes.size == 4 }
    return ctor?.newInstance(name, driver, url, user)
}

fun parseTreePattern(pattern: String): Any? {
    val cls = findClass(
        "com.intellij.database.util.TreePatternUtils"
    ) ?: return null
    return callStatic(cls, "parse", false, pattern)
}

fun performAutoSyncTask(project: Any, dataSource: Any) {
    val cls = findClass(
        "com.intellij.database.util.DataSourceUtil"
    ) ?: return
    callStatic(cls, "performAutoSyncTask", project, dataSource)
}

fun addDataSourceToManagers(project: Any, localManager: DataSourceManager<*>, dataSource: Any) {
    if (tryAddDataSource(localManager, dataSource)) return
    val managers = getManagers(project)
    managers.forEach { manager ->
        if (manager != localManager && tryAddDataSource(manager, dataSource)) return
    }
}

fun tryAddDataSource(manager: DataSourceManager<*>, dataSource: Any): Boolean {
    val raw = dataSource as? RawDataSource
    if (raw != null) {
        @Suppress("UNCHECKED_CAST")
        (manager as DataSourceManager<RawDataSource>).addDataSource(raw)
        return true
    }
    if (hasMethod(manager, "addDataSource", arrayOf(dataSource))) {
        call(manager, "addDataSource", dataSource)
        return true
    }
    return false
}

fun findClass(vararg names: String): Class<*>? {
    for (name in names) {
        try {
            return Class.forName(name)
        } catch (_: Throwable) {
        }
    }
    return null
}

fun findClassWithLoader(loader: ClassLoader?, vararg names: String): Class<*>? {
    for (name in names) {
        try {
            return Class.forName(name, true, loader)
        } catch (_: Throwable) {
        }
    }
    return null
}

fun callStatic(clazz: Class<*>, name: String, vararg args: Any?): Any? {
    val method = findMethod(clazz, name, args)
    return method?.invoke(null, *args)
}

fun call(target: Any?, name: String, vararg args: Any?): Any? {
    if (target == null) return null
    val method = findMethod(target.javaClass, name, args)
    return method?.invoke(target, *args)
}

fun callAccessible(target: Any?, name: String, vararg args: Any?): Any? {
    if (target == null) return null
    val method = findDeclaredMethod(target.javaClass, name, args) ?: return null
    method.isAccessible = true
    return method.invoke(target, *args)
}

fun findMethod(clazz: Class<*>, name: String, args: Array<out Any?>): java.lang.reflect.Method? {
    val methods = clazz.methods.filter { it.name == name && it.parameterTypes.size == args.size }
    if (methods.isEmpty()) return null
    if (methods.size == 1) return methods[0]
    return methods.firstOrNull { method ->
        method.parameterTypes.withIndex().all { (i, param) ->
            val arg = args[i]
            if (arg == null) {
                !param.isPrimitive
            } else {
                param.isAssignableFrom(arg.javaClass) || wrapPrimitive(param).isAssignableFrom(arg.javaClass)
            }
        }
    } ?: methods[0]
}

fun hasMethod(target: Any, name: String, args: Array<out Any?>): Boolean {
    return findMethod(target.javaClass, name, args) != null
}

fun debugSummary(tag: String, manager: DataSourceManager<*>, dataSource: Any) {
    val name = (dataSource as? DasDataSource)?.name ?: call(dataSource, "getName") as? String
    val storage = call(dataSource, "getPasswordStorage")?.toString()
    val loader = dataSource.javaClass.classLoader
    val credsClass = findClassWithLoader(loader, "com.intellij.database.access.DatabaseCredentials")
    val creds = if (credsClass != null) callStatic(credsClass, "getInstance") else null
    val hasPwd = if (creds != null && dataSource is DasDataSource) {
        val pwd = call(creds, "getPassword", dataSource) as? OneTimeString
        pwd != null
    } else {
        null
    }
    val persist = findClassWithLoader(loader, "com.intellij.database.dataSource.LocalDataSource\$Storage")
        ?.enumConstants
        ?.firstOrNull { it.toString() == "PERSIST" }
    show("summary[$tag] manager=${manager.javaClass.name}, ds=${dataSource.javaClass.name}, name=$name, storage=$storage, persistAvailable=${persist != null}, credsAvailable=${creds != null}, passwordStored=$hasPwd")
}

fun findDeclaredMethod(clazz: Class<*>, name: String, args: Array<out Any?>): java.lang.reflect.Method? {
    val methods = clazz.declaredMethods.filter { it.name == name && it.parameterTypes.size == args.size }
    if (methods.isEmpty()) return null
    if (methods.size == 1) return methods[0]
    return methods.firstOrNull { method ->
        method.parameterTypes.withIndex().all { (i, param) ->
            val arg = args[i]
            if (arg == null) {
                !param.isPrimitive
            } else {
                param.isAssignableFrom(arg.javaClass) || wrapPrimitive(param).isAssignableFrom(arg.javaClass)
            }
        }
    } ?: methods[0]
}

fun wrapPrimitive(type: Class<*>): Class<*> {
    return when (type) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        else -> type
    }
}
