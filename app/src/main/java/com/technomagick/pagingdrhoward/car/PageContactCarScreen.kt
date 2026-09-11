package com.technomagick.pagingdrhoward.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import com.technomagick.pagingdrhoward.data.PageLevel
import com.technomagick.pagingdrhoward.data.PairedContact

class PageContactCarScreen(
    carContext: CarContext,
    private val contact: PairedContact
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val heyLookAction = Action.Builder()
            .setTitle("Hey Look! 👀")
            .setBackgroundColor(CarColor.YELLOW)
            .setOnClickListener {
                sendPage(PageLevel.HEY_LOOK)
            }
            .build()

        val sosAction = Action.Builder()
            .setTitle("SOS EMERGENCY 🚨")
            .setBackgroundColor(CarColor.RED)
            .setOnClickListener {
                sendPage(PageLevel.SOS)
            }
            .build()

        return MessageTemplate.Builder("Select alert level to send to ${contact.name}:")
            .setTitle(contact.name)
            .setHeaderAction(Action.BACK)
            .addAction(heyLookAction)
            .addAction(sosAction)
            .build()
    }

    private fun sendPage(level: PageLevel) {
        CarToast.makeText(
            carContext,
            "Sending ${level.title} to ${contact.name}...",
            CarToast.LENGTH_SHORT
        ).show()

        PageSenderHelper.sendRemotePage(carContext, contact, level) { success, msg ->
            carContext.getCarService(androidx.car.app.AppManager::class.java)?.let {
                // If needed, we can show a follow-up toast or notification
            }
        }

        screenManager.pop()
    }
}
