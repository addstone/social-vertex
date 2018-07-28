package cn.net.polyglot.verticle

import com.google.common.collect.HashBiMap
import com.sun.deploy.util.BufferUtil.MB
import io.vertx.core.AbstractVerticle
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject
import io.vertx.core.net.NetServerOptions
import io.vertx.core.net.NetSocket

/**
 * @author zxj5470
 * @date 2018/7/9
 */
class IMTcpServerVerticle : AbstractVerticle() {

  private val socketMap = HashBiMap.create<NetSocket, String>()
  private var buffer = Buffer.buffer()

  override fun start() {
    val port = config().getInteger("tcp-port")

    vertx.eventBus().consumer<JsonObject>(this::class.java.name) {
      val type = it.body().getString("type")
      when (type) {
        "friend" -> {
          val action = it.body().getString("action")
          when (action) {
            "request" -> {
              val target = it.body().getString("to")
              val socketMap = socketMap.inverse()
              socketMap[target]?.write(it.body().toString().plus("\r\n"))
            }
          }
        }
        "propel"->{
          val result = it.body()
          val infomTarget = result.getJsonArray("target")
          val socketMap   = socketMap.inverse()
          for (json in infomTarget){
            val target = json as JsonObject
            val socket = socketMap[target.getString("id")]
            if (socket!=null){
              socket.write(result.toString().plus("\r\n"))
            }else{
              it.reply(JsonObject()
                .put("status","succeed"))
            }
          }

        }
      }
    }
    vertx.createNetServer(NetServerOptions().setTcpKeepAlive(true)).connectHandler { socket ->

      //因为是bimap，不能重复存入null，会抛异常，所以临时先放一个字符串，等用户登陆之后便会替换该字符串，以用户名取代
      socketMap[socket] = socket.writeHandlerID()

      socket.handler {
        buffer.appendBuffer(it)
        if (buffer.toString().endsWith("\r\n")) {
          val msgs = buffer.toString().substringBeforeLast("\r\n").split("\r\n")
          for (s in msgs) {
            processJsonString(s, socket)
          }
          buffer = Buffer.buffer()
        }

        if (buffer.length() > 1 * MB) {
          buffer = Buffer.buffer()
        }
      }

      socket.closeHandler {
        socketMap.remove(socket)
      }

      socket.exceptionHandler { e ->
        e.printStackTrace()
        socket.close()
      }
    }.listen(port) {
      if (it.succeeded()) {
        println("${this::class.java.name} is deployed")
      } else {
        println("${this::class.java.name} fail to deploy")
      }
    }
  }

  private fun processJsonString(jsonString: String, socket: NetSocket) {
    val result = JsonObject()
    try {
      val json = JsonObject(jsonString).put("from", socketMap[socket])

      when (json.getString("type")) {
        "user","search" -> vertx.eventBus().send<JsonObject>(IMMessageVerticle::class.java.name, json) {
          val resultJson = it.result().body()

          if (resultJson.containsKey("login")&&resultJson.getBoolean("login"))
             socketMap[socket] = json.getString("user")

          resultJson.put("type","user").put("action","login")

          socket.write(resultJson.toBuffer())
        }
        else -> {
          vertx.eventBus().send(IMMessageVerticle::class.java.name, json)
        }
      }
    } catch (e: Exception) {
      socket.write(result.put("info", e.message).toBuffer())
    }
  }

}
