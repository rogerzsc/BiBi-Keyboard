/**
 * 数字助手角色（fork 新增）。
 *
 * 被系统选为「助手应用」后，长按电源键 / 助手手势拉起会话，
 * 会话直接把悬浮球录音拉起来（复用音量键起录通道），随后收起自己。
 * 录音照常走 BiBI 引擎矩阵与 HomeRail 直达（含 TTS 回播）。
 *
 * 归属模块：api
 */
package com.brycewg.asrkb.api

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log
import com.brycewg.asrkb.ui.floating.FloatingAsrService

class BibiVoiceInteractionService : VoiceInteractionService()

class BibiVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle): VoiceInteractionSession =
        BibiVoiceInteractionSession(this)
}

class BibiVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, sourceFlags: Int) {
        super.onShow(args, sourceFlags)
        val intent = Intent(context, FloatingAsrService::class.java)
            .setAction(FloatingAsrService.ACTION_VOLUME_KEY_START)
        try {
            context.startForegroundService(intent)
        } catch (t: Throwable) {
            Log.w("BibiAssistant", "startForegroundService failed, fallback", t)
            try {
                context.startService(intent)
            } catch (t2: Throwable) {
                Log.w("BibiAssistant", "startService failed", t2)
            }
        }
        hide()
    }
}
