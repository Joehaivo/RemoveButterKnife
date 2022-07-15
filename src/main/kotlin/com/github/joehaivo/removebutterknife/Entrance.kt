package com.github.joehaivo.removebutterknife

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ArrayUtil
import com.github.joehaivo.removebutterknife.utils.Logger
import com.github.joehaivo.removebutterknife.utils.Notifier
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProgressManager


class Entrance(private val e: AnActionEvent) {
    private val project = e.project
    private var currFileIndex = 0
    private var parsedFileCount = 0
    private var exceptionFileCount = 0
    private var javaFileCount = 0
    var interrupt = false

    var onProgressUpdate: ((currFile: VirtualFile, currJavaFileIndex: Int, totalJavaFileCount: Int) -> Unit)? = null

    fun run() {
        // 多选文件、多级目录、单目录、单文件
        val vFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        calJavaFileCount(vFiles)
        if (javaFileCount > 1) {
            showDialogWhenBatch { isContinue ->
                if (isContinue) {
                    startHandle(vFiles)
                }
            }
        } else {
            startHandle(vFiles)
        }
    }

    private fun startHandle(vFiles: Array<out VirtualFile>?) {
        if (!ArrayUtil.isEmpty(vFiles)) {
            ProgressManager.getInstance().runProcessWithProgressSynchronously({
                val progressIndicator = ProgressManager.getInstance().progressIndicator
                vFiles?.forEachIndexed { index, vFile ->
                    progressIndicator.checkCanceled()
                    progressIndicator.text2 = "($index/${vFiles.size}) '${vFile.name}'... "
                    progressIndicator.fraction = index.toDouble() / vFiles.size
                    handle(vFile)
                }
                showResult()
            }, "正在处理Java文件", true, project)
        }
//        vFiles?.forEachIndexed { index, vFile ->
//            handle(vFile)
//        }
//        showResult()
    }

    fun showDialogWhenBatch(nextAction: (isContinue: Boolean) -> Unit) {
        val dialogBuilder = DialogBuilder()
        dialogBuilder.setErrorText("你正在批量处理Java文件，总数$javaFileCount, 可能需要较长时间，是否继续？")
        dialogBuilder.setOkOperation {
            nextAction.invoke(true)
            dialogBuilder.dialogWrapper.close(0)
        }
        dialogBuilder.setCancelOperation {
            nextAction.invoke(false)
            dialogBuilder.dialogWrapper.close(0)
        }
        dialogBuilder.showModal(true)
    }

    private fun showResult() {
        Notifier.notifyInfo(
            e.project!!,
            "RemoveButterknife处理结果：${currFileIndex}个Java文件, ${parsedFileCount}个完成, ${exceptionFileCount}个异常"
        )
    }

    private fun handle(it: VirtualFile) {
        if (it.isDirectory) {
            handleDirectory(it)
        } else {
            if (it.fileType is JavaFileType) {
                val psiFile = PsiManager.getInstance(e.project!!).findFile(it)
                val psiClass = PsiTreeUtil.findChildOfAnyType(psiFile, PsiClass::class.java)
                handleSingleVirtualFile(it, psiFile, psiClass)
            }
        }
    }

    private fun handleDirectory(dir: VirtualFile) {
        dir.children.forEach {
            handle(it)
        }
    }


    private fun handleSingleVirtualFile(vJavaFile: VirtualFile?, psiFile: PsiFile?, psiClass: PsiClass?) {
        if (interrupt) return
        if (vJavaFile != null && psiFile is PsiJavaFile && psiClass != null) {
            currFileIndex++
            onProgressUpdate?.invoke(vJavaFile, currFileIndex, javaFileCount)
            try {
                writeAction(psiFile) {
                    val parsed = ButterActionDelegate(e, psiFile, psiClass).parse()
                    if (parsed) {
                        parsedFileCount++
                        Notifier.notifyInfo(e.project!!, "$currFileIndex. ${vJavaFile.name} 处理结束 √ ")
                    } else {
                        Notifier.notifyInfo(e.project!!, "$currFileIndex. ${vJavaFile.name}没有找到butterknife的相关引用，不处理 - ")
                    }
                }
            } catch (t: Throwable) {
                t.printStackTrace()
                exceptionFileCount++
                Notifier.notifyError(e.project!!, "$currFileIndex. ${vJavaFile.name} 处理结束 × ")
            }
        }
    }

    fun calJavaFileCount(vFiles: Array<VirtualFile>?) {
        javaFileCount = 0
        if (!ArrayUtil.isEmpty(vFiles)) {
            vFiles?.forEach { vFile ->
                count(vFile)
            }
        }
    }

    private fun count(it: VirtualFile) {
        if (it.isDirectory) {
            it.children.forEach {
                count(it)
            }
        } else {
            if (it.fileType is JavaFileType) {
                val psiFile = PsiManager.getInstance(e.project!!).findFile(it)
                val psiClass = PsiTreeUtil.findChildOfAnyType(psiFile, PsiClass::class.java)
                if (psiFile is PsiJavaFile && psiClass != null) {
                    javaFileCount++
                }
            }
        }
    }

    private fun writeAction(
        psiJavaFile: PsiFile,
        commandName: String = "RemoveButterknifeWriteAction",
        runnable: Runnable
    ) {
        WriteCommandAction.runWriteCommandAction(project, commandName, "RemoveButterknifeGroupID", runnable, psiJavaFile)
//        ApplicationManager.getApplication().runWriteAction(runnable)
    }
}