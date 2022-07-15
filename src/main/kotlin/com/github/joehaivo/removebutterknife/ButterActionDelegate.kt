package com.github.joehaivo.removebutterknife

import com.intellij.lang.java.JavaImportOptimizer
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.*
import com.intellij.psi.impl.source.tree.java.PsiReferenceExpressionImpl
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.util.ArrayUtil
import com.github.joehaivo.removebutterknife.utils.Logger
import com.github.joehaivo.removebutterknife.utils.Notifier
import com.intellij.openapi.application.runWriteAction
import java.util.function.Predicate


class ButterActionDelegate(
    private val e: AnActionEvent,
    private val psiJavaFile: PsiJavaFile,
    private val psiClass: PsiClass
) {
    private val project = e.project
    private val elementFactory = JavaPsiFacade.getInstance(project!!).elementFactory

    /**
     * 代码插入(findViewById和__bindClicks())所在的锚点方法 eg: fun onCreate() | fun onCreateView()
     */
    private var anchorMethod: PsiMethod? = null

    /**
     * 代码插入所在的锚点语句 // eg: Butterknife.bind() | super.onCreate() | 内部类的super(view)
     */
    private var anchorStatement: PsiStatement? = null

    /**
     * unbinder = Butterknife.bind(this, view)表达式中的参数view
     */
    private var butterknifeView: String? = null

    /**
     * unbinder = Butterknife.bind(this, view) | Butterknife.bind(this, view) | Butterknife.bind(this)
     */
    private var butterknifeBindStatement: PsiStatement? = null

    private var deBouncingClass: PsiClass? = null

    fun parse(): Boolean {
        if (!checkIsNeedModify()) {
            return false
        }
        replaceDebouncingOnClickListener()

        val (bindViewFields, bindViewAnnotations) = collectBindViewAnnotation(psiClass.fields)
        val (bindClickVos, onClickAnnotations) = collectOnClickAnnotation(psiClass)
        if (bindClickVos.isNotEmpty() || bindViewFields.isNotEmpty()) {
            val pair = findAnchors(psiClass)
            if (pair.second == null || pair.first == null) {
//            targetInsertFindViewPair = genOverrideOnCreate()
                Notifier.notifyError(project!!, "RemoveButterKnife tools: 未在文件${psiClass.name}找到合适的代码插入位置，跳过")
                Logger.error("${anchorMethod}, $anchorStatement should not be null!")
            } else {
                anchorMethod = pair.first
                anchorStatement = pair.second
                insertBindViewsMethod(psiClass, bindViewFields, bindViewAnnotations)
                insertBindClickMethod(psiClass, bindClickVos, onClickAnnotations)
            }
        }

        deleteButterKnifeStatement(psiClass)

        deleteImportButterKnife()

        // 内部类
        handleInnerClass(psiClass.innerClasses)
        return true
    }

    private fun checkIsNeedModify(): Boolean {
        val importStatement = psiJavaFile.importList?.importStatements?.find {
            it.qualifiedName?.toLowerCase()?.contains("butterknife") == true
        }
        return importStatement != null
    }

    private fun deleteButterKnifeStatement(psiClass: PsiClass) {
        writeAction {
            if (anchorStatement?.text?.contains("ButterKnife") == true) {
                anchorStatement?.delete()
            }
            try {
                butterknifeBindStatement?.delete()
            } catch (t: Throwable) {
                t.printStackTrace()
            }
            // 再找一遍是否存在"ButterKnife.bind("
            val bindStates = mutableListOf<PsiStatement?>()
            psiClass.methods.forEach {
                val list = it.body?.statements?.filter { st ->
                    st.firstChild.text.trim().contains("ButterKnife.bind(")
                }
                if (list != null) {
                    bindStates.addAll(list)
                }
            }
            bindStates.forEach { it?.delete() }

            // unbinderField: private Unbinder bind;
            val unbinderField = psiClass.fields.find {
                it.type.canonicalText.contains("Unbinder")
            }
            if (unbinderField != null) {
                psiClass.methods.forEach {
                    it.body?.statements?.forEach { state ->
                        val theState = state.firstChild
                        // theState： unbinder.unbind();
                        if (theState is PsiMethodCallExpression) {
                            val unbinderRef = theState.firstChild.firstChild as? PsiReferenceExpressionImpl
                            if (unbinderField.type == unbinderRef?.type) {
                                state?.delete()
                            }
                        }
                        // state： if (unbinder != null) {}
                        if (state is PsiIfStatement) {
                            val child = state.condition?.firstChild
                            // 若第一个变量类型是unbinder， 则把这个if语句整个删除
                            if (child is PsiReferenceExpression) {
                                if (child.type == unbinderField.type) {
                                    state.delete()
                                }
                            }
                        }
                    }
                }
                unbinderField.delete()
            }
        }
    }

    // 寻找代码插入的锚点：例如onCreate()方法以及内部ButterKnife.bind()语句
    private fun findAnchors(psiClass: PsiClass): Pair<PsiMethod?, PsiStatement?> {
        var pair = findButterKnifeBind(psiClass)
        if (pair.second == null) {
            pair = findOnCreateView(psiClass)
        }
        if (pair.second == null) {
            pair = findOnViewCreated(psiClass)
        }
        if (pair.second == null) {
            pair = findOnCreate(psiClass)
        }
        if (pair.second == null) {
            pair = insertInflaterViewStatementOnCreateView()
        }
        if (pair.second == null) {
            pair = findConstructorAsAnchor(psiClass)
        }
        if (pair.second == null) {
            pair = createOnCreateViewMethodInFragment(psiClass)
        }
        return pair
    }

    private fun insertBindViewsMethod(
        psiClass: PsiClass,
        bindViewFields: Map<String, PsiField>,
        bindViewAnnotations: MutableList<PsiAnnotation>
    ) {
        if (bindViewFields.isEmpty()) {
            return
        }
        // 构建__bindViews()方法及方法体
        val args = if (butterknifeView.isNullOrEmpty()) "" else "View $butterknifeView"
        var bindViewsMethod =
            elementFactory.createMethodFromText("private void __bindViews(${args}) {}\n", this.psiClass)
        writeAction {
            val caller = if (butterknifeView.isNullOrEmpty()) "" else "${butterknifeView}."
            bindViewFields.forEach { (R_id_view, psiField) ->
                val findViewStr = "${psiField.name} = ${caller}findViewById($R_id_view);\n"
                val findViewState = elementFactory.createStatementFromText(findViewStr, this.psiClass)
                bindViewsMethod.lastChild.add(findViewState)
            }
            // 将__bindViews(){}插入到anchorMethod之后
            try {
                bindViewsMethod = psiClass.addAfter(bindViewsMethod, anchorMethod) as PsiMethod
            } catch (e: Exception) {
                e.printStackTrace()
            }
            // 将__bindViews();调用插入到anchorMethod里anchorStatement之后
            val para = if (butterknifeView == null) "" else butterknifeView
            val callBindViewsState = elementFactory.createStatementFromText("__bindViews($para);\n", this.psiClass)
            anchorStatement =
                anchorMethod?.addAfter(callBindViewsState, anchorStatement) as? PsiStatement
            bindViewAnnotations.forEach {
                it.delete()
            }
        }
    }

    // 删除import butterKnife.*语句
    private fun deleteImportButterKnife() {
        psiJavaFile.importList?.importStatements?.filter {
            it.qualifiedName?.contains("butterknife") == true
        }?.forEach { importStatement ->
            writeAction {
                importStatement.delete()
            }
        }
    }

    // 找到`ButterKnife.bind(`语句及所在方法
    private fun findButterKnifeBind(psiClass: PsiClass): Pair<PsiMethod?, PsiStatement?> {
        val pair = findStatement(psiClass) {
            it.firstChild.text.trim().contains("ButterKnife.bind(")
        }
        if (pair.second != null) {
            butterknifeBindStatement = pair.second
        }

        val theBindState = butterknifeBindStatement?.firstChild
        // 针对内部类的, firstChild: ButterKnife.bind(this，itemView)
        if (theBindState is PsiMethodCallExpression) {
            if (theBindState.argumentList.expressionCount == 2) {
                butterknifeView = theBindState.argumentList.expressions.lastOrNull()?.text
            }
        }
        // firstChild: unbinder = ButterKnife.bind(this, view)
        if (theBindState is PsiAssignmentExpression) {
            val bindMethodCall = theBindState.lastChild
            if (bindMethodCall is PsiMethodCallExpression && bindMethodCall.argumentList.expressionCount == 2) {
                butterknifeView = bindMethodCall.argumentList.expressions.lastOrNull()?.text
            }
        }
        return pair
    }

    // 找到`super.onCreateView(`语句及所在方法
    private fun findOnCreateView(psiClass: PsiClass): Pair<PsiMethod?, PsiStatement?> {
        val pair = findStatement(psiClass) {
            it.firstChild.text.trim().contains("super.onCreateView(")
        }
        if (pair.second != null) {
            butterknifeView = "view"
        }
        return pair
    }

    private fun findOnViewCreated(psiClass: PsiClass): Pair<PsiMethod?, PsiStatement?> {
        val pair: Pair<PsiMethod?, PsiStatement?> = Pair(null, null)
        val onViewCreatedMethod = psiClass.methods.find { it.text.contains("onViewCreated(") } ?: return pair
        val firstState = onViewCreatedMethod.body?.statements?.firstOrNull() ?: return pair
        butterknifeView = "view"
        return pair.copy(onViewCreatedMethod, firstState)
    }

    // 找到`super.onCreate(`语句及所在方法
    private fun findOnCreate(psiClass: PsiClass): Pair<PsiMethod?, PsiStatement?> {
        return findStatement(psiClass) {
            it.firstChild.text.trim().startsWith("super.onCreate(")
        }
    }

    // 当存在provideLayout()，并且没有找到ButterKnife.bind( | super.onCreateView( | super.onCreate(时， 插入View _view = inflater.inflate()
    private fun insertInflaterViewStatementOnCreateView(): Pair<PsiMethod?, PsiStatement?> {
        var pair: Pair<PsiMethod?, PsiStatement?> = Pair(null, null)
        val onCreateViewMethod = psiClass.methods.find {
            it.text.contains("View onCreateView(")
        } ?: return pair
        val provideLayoutMethod = psiClass.methods.find {
            it.text.contains("int provideLayout(")
        } ?: return pair
        val onCreateViewParams = onCreateViewMethod.parameterList.parameters
        if (onCreateViewParams.size == 3) {
            val inflateViewState = elementFactory.createStatementFromText(
                "View _view = ${onCreateViewParams[0].name}.inflate(provideLayout(), ${onCreateViewParams[1].name}, false);",
                psiClass
            )
            var insertedState: PsiElement? = null
            writeAction {
                val body = onCreateViewMethod.body
                if (body != null && body.statementCount > 0) {
                    insertedState = body.addBefore(inflateViewState, body.statements[0])
                } else {
                    insertedState = body?.add(inflateViewState)
                }
            }
            if (insertedState != null) {
                butterknifeView = "_view"
                pair = Pair(onCreateViewMethod, insertedState as PsiStatement)
            }
        }
        return pair
    }

    private fun findConstructorAsAnchor(psiClass: PsiClass): Pair<PsiMethod?, PsiStatement?> {
        var pair: Pair<PsiMethod?, PsiStatement?> = Pair(null, null)
        if (!ArrayUtil.isEmpty(psiClass.constructors)) {
            val targetConstructor = psiClass.constructors.find {
                val pView = it.parameterList.parameters.find { p ->
                    p.type.canonicalText.contains("View")
                }
                if (pView != null) {
                    butterknifeView = pView.name
                }
                pView != null
            }
            if (!ArrayUtil.isEmpty(targetConstructor?.body?.statements)) {
                pair = Pair(targetConstructor, targetConstructor?.body?.statements?.get(0)!!)
            }
        }
        return pair
    }

    private fun createOnCreateViewMethodInFragment(psiClass: PsiClass): Pair<PsiMethod?, PsiStatement?> {
        var pair: Pair<PsiMethod?, PsiStatement?> = Pair(null, null)
        val anchorMethod = psiClass.methods.find {
            it.text.contains("myInit(") // 目前myInit()方法只存在于Fragment
        } ?: return pair

        val onCreateViewMethod = elementFactory.createMethodFromText(
            "@Override\n" +
                    "    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) { }",
            psiClass
        )
        val statement1 = elementFactory.createStatementFromText(
            "View view = inflater.inflate(provideLayout(), container, false);",
            psiClass
        )
        val statement2 = elementFactory.createStatementFromText(
            "return super.onCreateView(inflater, container, savedInstanceState);",
            psiClass
        )

        writeAction {
            val method = psiClass.addBefore(onCreateViewMethod, anchorMethod) as? PsiMethod
            val anchorStatement = method?.lastChild?.add(statement1) as? PsiStatement
            method?.lastChild?.add(statement2)
            pair = pair.copy(method, anchorStatement)
            val importOptimizer = JavaImportOptimizer()
            if (importOptimizer.supports(psiJavaFile)) {
                importOptimizer.processFile(psiJavaFile).run()
            }
        }
        butterknifeView = "view"
        return pair
    }

    fun genOverrideOnCreate(): Pair<PsiMethod?, PsiStatement?> {
        val onCreateMethod = elementFactory.createMethodFromText(
            "" +
                    "protected void onCreate(Bundle savedInstanceState) { }", psiClass
        )
        val callSuperStatement =
            elementFactory.createStatementFromText("super.onCreate(savedInstanceState);\n", psiClass)
        val firstMethod = psiClass.methods.firstOrNull()
        writeAction {
            onCreateMethod.lastChild.add(callSuperStatement)
            if (firstMethod != null) {
                psiClass.addAfter(onCreateMethod, firstMethod)
            } else {
                psiClass.add(onCreateMethod)
            }
//            callback(Pair(onCreateMethod, callSuperStatement))
        }
        return Pair(onCreateMethod, callSuperStatement)
    }


    private fun findStatement(psiClass: PsiClass, predicate: Predicate<PsiStatement>): Pair<PsiMethod?, PsiStatement?> {
        var bindState: PsiStatement? = null
        val bindMethod = psiClass.methods.find { psiMethod ->
            bindState = psiMethod.body?.statements?.find { psiStatement ->
                predicate.test(psiStatement)
            }
            bindState != null
        }
        return Pair(bindMethod, bindState)
    }

    // 遍历psiFields找到包含BindView注解的字段
    private fun collectBindViewAnnotation(psiFields: Array<PsiField>): Pair<MutableMap<String, PsiField>, MutableList<PsiAnnotation>> {
        val knifeFields: MutableMap<String, PsiField> = mutableMapOf() // eg: {"rv_list": PsiField}
        val bindViewAnnotations = mutableListOf<PsiAnnotation>()
        psiFields.forEach {
            it.annotations.forEach { psiAnnotation: PsiAnnotation ->
                // 记录这个psiField, 将BindView注解删掉
                if (psiAnnotation.qualifiedName?.contains("BindView") == true) {
                    val R_id_view = psiAnnotation.findAttributeValue("value")?.text?.replace("R2", "R")
                    if (R_id_view != null) {
                        knifeFields[R_id_view] = it
                        bindViewAnnotations.add(psiAnnotation)
                    }
                }
            }
        }
        return Pair(knifeFields, bindViewAnnotations)
    }

    private fun insertBindClickMethod(
        psiClass: PsiClass,
        onClickVOs: MutableList<BindClickVO>,
        onClickAnnotations: MutableList<PsiAnnotation>
    ) {
        if (onClickVOs.isEmpty()) {
            return
        }
        writeAction {
            // 构建__bindClicks()方法体
            var caller = ""
            val bindClickMethod = if (butterknifeView == null) {
                elementFactory.createMethodFromText("private void __bindClicks() {}\n", psiClass)
            } else {
                caller = "${butterknifeView}."
                elementFactory.createMethodFromText(
                    "private void __bindClicks(View ${butterknifeView}) {}\n", psiClass
                )
            }
            importMyDebouncingListenerIfAbsent()
            onClickVOs.forEach {
                val setClickState = elementFactory.createStatementFromText(
                    "${caller}findViewById(${it.viewId}).setOnClickListener((DebouncingOnClickListener) ${it.lambdaParam} -> ${it.callMethodExpr});",
                    psiClass
                )
                bindClickMethod.lastChild.add(setClickState)
            }
            val para = if (butterknifeView == null) "" else butterknifeView
            val callBindClickState = elementFactory.createStatementFromText("__bindClicks($para);\n", psiClass)
            // 插入__bindClicks()调用
            anchorStatement = anchorMethod?.addAfter(callBindClickState, anchorStatement) as? PsiStatement
            // 插入__bindClicks()方法体
            psiClass.addAfter(bindClickMethod, anchorMethod)
            onClickAnnotations.forEach {
                it.delete()
            }
            Logger.info("__bindClicks: ${bindClickMethod.text}")
        }
    }

    private fun importMyDebouncingListenerIfAbsent() {
        deBouncingClass ?: return
        val debouncingImportState = psiJavaFile.importList?.importStatements?.find {
            it.qualifiedName?.contains("DebouncingOnClickListener") == true
        }
        // import列表不存在DebouncingOnClickListener类，import它
        if (debouncingImportState == null) {
            writeAction {
                val statement = elementFactory.createImportStatement(deBouncingClass!!)
                psiJavaFile.addBefore(statement, psiJavaFile.importList?.importStatements?.lastOrNull())
            }
        }
    }

    private fun collectOnClickAnnotation(psiClass: PsiClass): Pair<MutableList<BindClickVO>, MutableList<PsiAnnotation>> {
        val onClickVOs: MutableList<BindClickVO> = mutableListOf()
        val annotations: MutableList<PsiAnnotation> = mutableListOf()
        psiClass.methods.forEach { method: PsiMethod ->
            method.annotations.forEach { annotation: PsiAnnotation ->
                if (annotation.qualifiedName?.contains("OnClick") == true) {
                    // 收集注解中的id
                    val attributeValue: PsiAnnotationMemberValue? = annotation.findAttributeValue("value")
                    // @OnClick()中是{id, id，...}或{id}的情况
                    if (attributeValue is PsiArrayInitializerMemberValue) {
                        attributeValue.initializers.forEach {
                            addBindClickVo(onClickVOs, method, it)
                        }
                    } else if (attributeValue is PsiAnnotationMemberValue) {
                        // @OnClick()中是单个id的情况
                        addBindClickVo(onClickVOs, method, attributeValue)
                    }
                    annotations.add(annotation)
                }
            }
        }
        return Pair(onClickVOs, annotations)
    }

    private fun addBindClickVo(
        onClickVOs: MutableList<BindClickVO>,
        method: PsiMethod,
        annotationMemberValue: PsiAnnotationMemberValue
    ) {
        val viewId = annotationMemberValue.text.replace("R2.", "R.")
        val lambdaParam = "_${if (butterknifeView.isNullOrEmpty()) "v" else butterknifeView}"
        val methodParam = if (method.parameterList.parameters.isNotEmpty()) lambdaParam else ""
        onClickVOs.add(BindClickVO(viewId, lambdaParam, "${method.name}($methodParam)"))
    }

    private fun implementViewClick() {
        val fullPkgName = "android.view.View.OnClickListener"
        val clickImpl = psiClass.implementsList?.referencedTypes?.find {
            it.canonicalText.contains("View.OnClickListener")
        }
        if (clickImpl != null) {
            return
        }
        val ref = elementFactory.createPackageReferenceElement(fullPkgName)
        val refClass = elementFactory.createType(ref)
        val referenceElement = elementFactory.createReferenceElementByType(refClass)
        writeAction {
            psiClass.implementsList?.add(referenceElement)
        }
    }

    private fun writeAction(commandName: String = "RemoveButterknifeWriteAction", runnable: Runnable) {
        WriteCommandAction.runWriteCommandAction(project, commandName, "RemoveButterknifeGroupID", runnable, psiJavaFile)
//        ApplicationManager.getApplication().runWriteAction(runnable)
    }

    private fun replaceDebouncingOnClickListener() {
        if (deBouncingClass == null) {
            val fullClassName = "DebouncingOnClickListener"
            val searchScope = GlobalSearchScope.allScope(project!!)
            val psiClasses = PsiShortNamesCache.getInstance(project).getClassesByName(fullClassName, searchScope)
            deBouncingClass = psiClasses.find {
                it.qualifiedName?.contains("butterknife") == false
            }
        }
        val importDebouncingStatement = psiJavaFile.importList?.importStatements?.find {
            it.qualifiedName?.contains("butterknife.internal.DebouncingOnClickListener") == true
        }
        if (importDebouncingStatement == null) {
            return
        }
        runWriteAction {
            if (deBouncingClass != null) {
                val statement = elementFactory.createImportStatement(deBouncingClass!!)
                try {
                    psiJavaFile.addBefore(statement, importDebouncingStatement)
                    importDebouncingStatement.delete()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                Notifier.notifyError(project!!, "RemoveButterKnife tool: ${psiClass.name}没有找到可替代的DebouncingOnClickListener类，跳过！")
            }
        }
    }

    private fun handleInnerClass(innerClasses: Array<PsiClass>?) {
        innerClasses?.forEach {
            anchorMethod = null
            anchorStatement = null
            butterknifeView = null
            butterknifeBindStatement = null
            val (bindViewFields, bindViewAnnotations) = collectBindViewAnnotation(it.fields)
            val (bindClickVos, onClickAnnotations) = collectOnClickAnnotation(it)
            if (bindClickVos.isNotEmpty() || bindViewFields.isNotEmpty()) {
                val pair = findAnchors(it)
                if (pair.second == null || pair.first == null) {
                    Notifier.notifyError(project!!, "RemoveButterKnife tools: 未在内部类${it.name}找到合适的代码插入位置，跳过")
                    Logger.error("${anchorMethod}, $anchorStatement should not be null!")
                } else {
                    anchorMethod = pair.first
                    anchorStatement = pair.second
                    insertBindClickMethod(it, bindClickVos, onClickAnnotations)
                    insertBindViewsMethod(it, bindViewFields, bindViewAnnotations)
                }
            }
            deleteButterKnifeStatement(it)
            handleInnerClass(it.innerClasses)
        }
    }
}