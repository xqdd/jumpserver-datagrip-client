import com.intellij.credentialStore.OneTimeString
import com.intellij.database.access.DatabaseCredentials
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.psi.DataSourceManager
import com.intellij.database.util.DataSourceUtil
import com.intellij.database.util.TreePatternUtils
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.impl.ExtensionPointImpl
import com.intellij.openapi.project.ProjectManager
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.QueryStringDecoder
import liveplugin.show
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
        show(parameters)

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

val handlers: ExtensionPointImpl<Any> =
    (ApplicationManager.getApplication().extensionArea.getExtensionPoint<Any>("com.intellij.httpRequestHandler")) as ExtensionPointImpl<Any>

handlers.findExtensions(Any::class.java).forEach {
    if (it.javaClass.name.startsWith("Plugin\$")) {
        show("unregisterExtension: " + it.javaClass.name)
        handlers.unregisterExtension(it)
    }
}
handlers.registerExtension(service)

fun add(paramMap: Map<String, String>) {
    val proj = ProjectManager.getInstance().openProjects[0]
    val dm = DataSourceManager.getManagers(proj)[0] as LocalDataSourceManager
    val dbname = paramMap["name"]?.split("[")!![0].split("@")!![1]
    val user = paramMap["user"]!!
    val password = paramMap["password"]?.toCharArray()!!

    dm.dataSources.forEach {
        if (it.name == dbname) {
            it.username = user
            setPass(it, password)
            show("updated: " + dbname)
            return@add
        }
    }

    val ds = LocalDataSource.create(
        dbname,
        "com.mysql.cj.jdbc.Driver",
        "jdbc:mysql://${paramMap["host"]}:${paramMap["port"]}",
        user
    )
    ds.resolveDriver()
    setPass(ds, password)
    ds.isAutoSynchronize = true
    ds.isGlobal = true
    ds.schemaMapping.introspectionScope = TreePatternUtils.parse(false, "*:*")
    dm.addDataSource(ds)
    DataSourceUtil.performAutoSyncTask(proj, ds)
    show("added: " + dbname)
}


fun setPass(ds: LocalDataSource, password: CharArray) {
    DatabaseCredentials.getInstance().credentialManager.setPassword(
        ds,
        OneTimeString(password),
        LocalDataSource.Storage.PERSIST
    )
}
