/*
 * Copyright 2011-2020 GatlingCorp (https://gatling.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.gatling.http.action.ws.fsm

import java.util.concurrent.{ ScheduledFuture, TimeUnit }

import io.gatling.commons.util.Clock
import io.gatling.core.action.Action
import io.gatling.core.session.Session
import io.gatling.core.stats.StatsEngine
import io.gatling.http.check.ws.{ WsBinaryFrameCheck, WsFrameCheck, WsFrameCheckSequence, WsTextFrameCheck }
import io.gatling.http.client.{ Request, WebSocket }
import io.gatling.http.engine.HttpEngine
import io.gatling.http.protocol.HttpProtocol
import io.netty.channel.EventLoop
import io.netty.handler.codec.http.cookie.Cookie

import scala.concurrent.duration.FiniteDuration

class WsFsm(
    private[fsm] val wsName: String,
    private[fsm] val connectRequest: Request,
    private[fsm] val subprotocol: Option[String],
    private[fsm] val connectActionName: String,
    private[fsm] val connectCheckSequence: List[WsFrameCheckSequence[WsFrameCheck]],
    private[fsm] val onConnected: Option[Action],
    private[fsm] val statsEngine: StatsEngine,
    private[fsm] val httpEngine: HttpEngine,
    private[fsm] val httpProtocol: HttpProtocol,
    private[fsm] val eventLoop: EventLoop,
    private[fsm] val clock: Clock
) {

  private var currentState: WsState = new WsInitState(this)
  private var _timeoutId = 0L // FIXME do we still need this?
  private var currentTimeout: ScheduledFuture[Unit] = _
  private[fsm] def scheduleTimeout(dur: FiniteDuration): Long = {
    val curr = _timeoutId
    eventLoop.schedule(() => currentState.onTimeout(curr), dur.toMillis, TimeUnit.MILLISECONDS)
    _timeoutId += 1
    curr
  }

  private[fsm] def cancelTimeout(): Unit =
    if (currentTimeout != null) {
      currentTimeout.cancel(true)
      currentTimeout = null
    }

  private var stashedSendFrame: SendFrame = _
  private[fsm] def stashSendFrame(sendFrame: SendFrame): Unit =
    stashedSendFrame = sendFrame
  private def unstashSendFrame(): Unit =
    if (stashedSendFrame != null) {
      val ref = stashedSendFrame
      stashedSendFrame = null
      ref match {
        case SendTextFrame(actionName, message, checkSequences, session, next) =>
          onSendTextFrame(actionName, message, checkSequences, session, next)
        case SendBinaryFrame(actionName, message, checkSequences, session, next) =>
          onSendBinaryFrame(actionName, message, checkSequences, session, next)
      }
    }

  def onPerformInitialConnect(session: Session, initialConnectNext: Action): Unit = {
    currentState = currentState.onPerformInitialConnect(session, initialConnectNext)
    unstashSendFrame()
  }

  def onWebSocketConnected(webSocket: WebSocket, cookies: List[Cookie], timestamp: Long): Unit = {
    currentState = currentState.onWebSocketConnected(webSocket, cookies, timestamp)
    unstashSendFrame()
  }

  def onSendTextFrame(
      actionName: String,
      message: String,
      checkSequences: List[WsFrameCheckSequence[WsTextFrameCheck]],
      session: Session,
      next: Action
  ): Unit = {
    currentState = currentState.onSendTextFrame(actionName, message, checkSequences, session, next)
    unstashSendFrame()
  }

  def onSendBinaryFrame(
      actionName: String,
      message: Array[Byte],
      checkSequences: List[WsFrameCheckSequence[WsBinaryFrameCheck]],
      session: Session,
      next: Action
  ): Unit = {
    currentState = currentState.onSendBinaryFrame(actionName, message, checkSequences, session, next)
    unstashSendFrame()
  }

  def onTextFrameReceived(message: String, timestamp: Long): Unit = {
    currentState = currentState.onTextFrameReceived(message, timestamp)
    unstashSendFrame()
  }

  def onBinaryFrameReceived(message: Array[Byte], timestamp: Long): Unit = {
    currentState = currentState.onBinaryFrameReceived(message, timestamp)
    unstashSendFrame()
  }

  def onWebSocketClosed(code: Int, reason: String, timestamp: Long): Unit = {
    currentState = currentState.onWebSocketClosed(code, reason, timestamp)
    unstashSendFrame()
  }

  def onWebSocketCrashed(t: Throwable, timestamp: Long): Unit = {
    currentState = currentState.onWebSocketCrashed(t, timestamp)
    unstashSendFrame()
  }

  def onClientCloseRequest(actionName: String, session: Session, next: Action): Unit = {
    currentState = currentState.onClientCloseRequest(actionName, session, next)
    unstashSendFrame()
  }

  def onTimeout(id: Long): Unit = {
    currentState = currentState.onTimeout(id)
    unstashSendFrame()
  }
}
