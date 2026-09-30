# ViewModel constructors are selected reflectively by Android's lifecycle factory.
-keepclassmembers class dev.pinkcollab.ui.CollabViewModel {
    public <init>(android.app.Application, androidx.lifecycle.SavedStateHandle);
}

# Keep useful source locations in retraced production stack traces.
-keepattributes SourceFile,LineNumberTable
