# W1 骨架：release 暂不 minify，规则文件留位。
# 后续轮次真要 minify 时，@JavascriptInterface 标注的方法默认会被剥掉，
# 必须保留 WebHostBridge 及其 addJavascriptInterface 传入的对象。
-keepclassmembers class com.xiufeigo.opencodeshell.web.** {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.xiufeigo.opencodeshell.web.** { *; }
