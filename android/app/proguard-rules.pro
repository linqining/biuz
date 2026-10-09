# BiuZ Android release 混淆规则（当前 isMinifyEnabled=false，本文件仅占位）。
# 后续启用 R8 时注意：
# - protocol 层使用自研 JsonCodec 按字段名反射-free 序列化，无 keep 需求；
# - 若引入 kotlinx-serialization/OkHttp 反射面，按其官方 keep 规则补充。
