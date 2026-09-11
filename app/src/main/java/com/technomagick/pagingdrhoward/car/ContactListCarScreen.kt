package com.technomagick.pagingdrhoward.car

import android.content.Context
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.*
import com.technomagick.pagingdrhoward.data.DefaultPagerRepository
import com.technomagick.pagingdrhoward.receiver.AlertActionReceiver
import com.technomagick.pagingdrhoward.service.EmergencyPagerService

class ContactListCarScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val prefs = carContext.getSharedPreferences(DefaultPagerRepository.PREF_NAME, Context.MODE_PRIVATE)
        val repository = DefaultPagerRepository(prefs)
        val contacts = repository.getPairedContacts()

        val isAlarmRinging = EmergencyPagerService.isAlarmActive

        if (contacts.isEmpty() && !isAlarmRinging) {
            return MessageTemplate.Builder("No paired contacts.\n\nPlease open the app on your phone to pair with family first.")
                .setTitle("Paging Dr. Howard")
                .setHeaderAction(Action.APP_ICON)
                .build()
        }

        val listBuilder = ItemList.Builder()

        // If an alarm is currently active, display an emergency dismiss row at the very top
        if (isAlarmRinging) {
            val sender = EmergencyPagerService.activeSender.ifBlank { "Family Member" }
            val dismissRow = Row.Builder()
                .setTitle("🚨 ACTIVE ALARM: $sender")
                .addText("Tap here immediately to Acknowledge & Dismiss")
                .setOnClickListener {
                    AlertActionReceiver.performDismiss(
                        context = carContext,
                        senderTopic = EmergencyPagerService.activeTopic,
                        alertTimestamp = EmergencyPagerService.activeTimestamp
                    )
                    CarToast.makeText(carContext, "Alarm Acknowledged & Silenced", CarToast.LENGTH_SHORT).show()
                    invalidate()
                }
                .build()
            listBuilder.addItem(dismissRow)
        }

        // Add each paired family contact
        for (contact in contacts) {
            val contactRow = Row.Builder()
                .setTitle(contact.name)
                .addText("Tap to send Hey Look or SOS page")
                .setOnClickListener {
                    screenManager.push(PageContactCarScreen(carContext, contact))
                }
                .build()
            listBuilder.addItem(contactRow)
        }

        val templateBuilder = ListTemplate.Builder()
            .setTitle(if (isAlarmRinging) "🚨 ALERT ACTIVE!" else "Paging Dr. Howard")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(listBuilder.build())

        // Also add an ActionStrip button for dismissing the alarm if sounding
        if (isAlarmRinging) {
            val dismissAction = Action.Builder()
                .setTitle("🔕 Dismiss")
                .setBackgroundColor(CarColor.RED)
                .setOnClickListener {
                    AlertActionReceiver.performDismiss(
                        context = carContext,
                        senderTopic = EmergencyPagerService.activeTopic,
                        alertTimestamp = EmergencyPagerService.activeTimestamp
                    )
                    CarToast.makeText(carContext, "Alarm Silenced", CarToast.LENGTH_SHORT).show()
                    invalidate()
                }
                .build()

            val actionStrip = ActionStrip.Builder()
                .addAction(dismissAction)
                .build()

            templateBuilder.setActionStrip(actionStrip)
        }

        return templateBuilder.build()
    }
}
