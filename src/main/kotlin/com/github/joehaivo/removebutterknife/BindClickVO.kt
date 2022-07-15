package com.github.joehaivo.removebutterknife

/**
 * 用于解析@BindView语句后的对象
 * @param viewId: R.id.tv_xxx
 * @param lambdaParam: lambda的参数。eg: _v
 * @param callMethodExpr: viewClicked(_v)
 */
data class BindClickVO(var viewId: String, var lambdaParam: String, var callMethodExpr: String)