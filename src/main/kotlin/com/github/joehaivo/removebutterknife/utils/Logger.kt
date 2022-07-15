package com.github.joehaivo.removebutterknife.utils

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

object Logger {
    fun info(message: String) {
        Logger.getInstance("RemoveButterKnife").info(message)
    }

    fun warn(message: String) {
        Logger.getInstance("RemoveButterKnife").warn(message)
    }

    fun error(message: String) {
        Logger.getInstance("RemoveButterKnife").error(message)
    }
}