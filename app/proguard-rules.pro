# GeckoView 大量通过 JNI / 反射回调 Delegate，保持 org.mozilla.geckoview 与本项目 Delegate 实现。
-keep class org.mozilla.geckoview.** { *; }
-keep class com.local.geckobrowser.browser.delegates.** { *; }
-keep class com.local.geckobrowser.data.** { *; }
-dontwarn org.mozilla.geckoview.**
