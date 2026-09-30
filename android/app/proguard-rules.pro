# ViewModel constructors are selected reflectively by Android's lifecycle factory.
-keepclassmembers class dev.pinkcollab.ui.CollabViewModel {
    public <init>(android.app.Application, androidx.lifecycle.SavedStateHandle);
}

# Markwon orders plugins using CorePlugin.class.isAssignableFrom(plugin.getClass()).
# Merging CorePlugin with our style plugin reverses their order and restores default spans.
-keep,allowobfuscation class io.noties.markwon.core.CorePlugin

# Keep useful source locations in retraced production stack traces.
-keepattributes SourceFile,LineNumberTable
