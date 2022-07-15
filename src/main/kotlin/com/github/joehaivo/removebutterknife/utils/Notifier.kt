package com.github.joehaivo.removebutterknife.utils

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object Notifier {
    private val notificationGroup = NotificationGroupManager.getInstance()
        .getNotificationGroup("RemoveButterKnife Notification Group")

    fun notifyError(project: Project, content: String) {
            notificationGroup.createNotification(content, NotificationType.ERROR).notify(project)
    }

    fun notifyInfo(project: Project, content: String) {
        notificationGroup.createNotification(content, NotificationType.INFORMATION).notify(project)
    }

    fun notifyWarning(project: Project, content: String) {
        notificationGroup.createNotification(content, NotificationType.WARNING).notify(project)
    }
}