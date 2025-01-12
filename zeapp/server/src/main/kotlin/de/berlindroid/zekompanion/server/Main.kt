@file:JvmName("Main")

package de.berlindroid.zekompanion.server

import de.berlindroid.zekompanion.server.ai.AI
import de.berlindroid.zekompanion.server.routers.adminCreateUser
import de.berlindroid.zekompanion.server.routers.adminCreateUserBadge
import de.berlindroid.zekompanion.server.routers.adminDeleteUser
import de.berlindroid.zekompanion.server.routers.adminListUsers
import de.berlindroid.zekompanion.server.routers.getOptimizedPosts
import de.berlindroid.zekompanion.server.routers.getPosts
import de.berlindroid.zekompanion.server.routers.getUser
import de.berlindroid.zekompanion.server.routers.getUserProfileImageBinary
import de.berlindroid.zekompanion.server.routers.getUserProfileImagePng
import de.berlindroid.zekompanion.server.routers.imageBin
import de.berlindroid.zekompanion.server.routers.imagePng
import de.berlindroid.zekompanion.server.routers.index
import de.berlindroid.zekompanion.server.routers.postPost
import de.berlindroid.zekompanion.server.routers.updateUser
import de.berlindroid.zekompanion.server.user.UserRepository
import de.berlindroid.zekompanion.server.zepass.ZePassRepository
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngineEnvironmentBuilder
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.tomcat.Tomcat
import io.ktor.server.tomcat.TomcatApplicationEngine
import java.io.File
import java.security.KeyStore

private const val SSL_PASSWORD_ENV = "SSL_CERTIFICATE_PASSWORD"
private const val KEYSTORE_RESOURCE_FILE = "/tmp/keystore.jks"

fun main() {
    val users = UserRepository.load()
    val zepass = ZePassRepository.load()

    embeddedBadgeServer(users, zepass)
        .start(wait = false)

    embeddedWebServer(users, zepass)
        .start(wait = true)
}

private fun embeddedBadgeServer(
    users: UserRepository,
    zepass: ZePassRepository,
): TomcatApplicationEngine {
    return embeddedServer(
        Tomcat,
        environment = applicationEngineEnvironment {
            connector {
                port = 1337
            }

            module {
                install(ContentNegotiation) {
                    json()
                }

                routing {
                    get("/") {
                        call.respondText("yes")
                    }

                    postPost(zepass, users)
                    getOptimizedPosts(zepass, users)
                }
            }
        },
    )
}

private fun embeddedWebServer(
    users: UserRepository,
    zepass: ZePassRepository,
): TomcatApplicationEngine {
    val keyPassword = try {
        System.getenv(SSL_PASSWORD_ENV)
    } catch (e: Exception) {
        null
    }

    val keyStore: KeyStore? = loadKeyStore(keyPassword)
    val ai = AI()

    return embeddedServer(
        Tomcat,
        environment = applicationEngineEnvironment {
            injectTLSIfNeeded(keyStore, keyPassword)

            module {
                install(ContentNegotiation) {
                    json()
                }

                routing {
                    staticResources("/", "static") {
                        index()
                        exclude { file ->
                            file.path.endsWith("db")
                        }
                    }

                    imageBin()
                    imagePng()

                    adminCreateUser(users, ai)
                    adminListUsers(users)
                    adminDeleteUser(users)
                    adminCreateUserBadge(users)

                    updateUser(users)
                    getUser(users)
                    getUserProfileImageBinary(users)
                    getUserProfileImagePng(users)

                    postPost(zepass, users)
                    getPosts(zepass)
                }
            }
        },
    )
}

private fun ApplicationEngineEnvironmentBuilder.injectTLSIfNeeded(keyStore: KeyStore?, keyPassword: String?) {
    if (keyStore != null && keyPassword != null) {
        sslConnector(
            keyStore = keyStore,
            keyAlias = "zealias",
            keyStorePassword = { keyPassword.toCharArray() },
            privateKeyPassword = { keyPassword.toCharArray() },
        ) {
            keyStorePath = File(KEYSTORE_RESOURCE_FILE)
        }
    }
}

private fun loadKeyStore(keyPassword: String?): KeyStore? {
    var keyStore: KeyStore? = null

    if (keyPassword == null) {
        println("... NO PASSWORD SET ON '$SSL_PASSWORD_ENV' ENVIRONMENT VAR -> NO SSL ENCRYPTION ...")
    } else {
        keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        val stream = jksInResourcesStream()
        val keyArray = keyPassword.toCharArray()
        keyStore.load(stream, keyArray)

        println("Certificate and password found.")
        println("• Found ${keyStore.aliases().toList().count()} aliases.")
        keyStore.aliases()?.asIterator()?.forEach { println("• '$it'.") }
    }

    return keyStore
}

private fun jksInResourcesStream() = object {}.javaClass.getResourceAsStream(KEYSTORE_RESOURCE_FILE)
