package com.technomagick.pagingdrhoward.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class PagerCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        // Allows connection from Android Auto head units and Desktop Head Unit emulator
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return object : Session() {
            override fun onCreateScreen(intent: Intent): Screen {
                return ContactListCarScreen(carContext)
            }
        }
    }
}
