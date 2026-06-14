package com.github.kr328.clash

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService

class CollapseActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (intent.action == "com.github.kr328.clash.TOGGLE") {
            val isRunning = com.github.kr328.clash.remote.StatusClient(this).currentProfile() != null
            
            if (isRunning) {
                stopClashService()
                Toast.makeText(this, "Stopped / 已停止", Toast.LENGTH_SHORT).show()
            } else {
                val vpnIntent = startClashService()
                if (vpnIntent != null) {
                    val wrapper = Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(wrapper)
                } else {
                    Toast.makeText(this, "Started / 已启动", Toast.LENGTH_SHORT).show()
                }
            }
        }
        finish()
    }
}
