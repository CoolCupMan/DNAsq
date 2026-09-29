package com.coolcupman.dnasq

import android.app.Application
import com.coolcupman.dnasq.core.LabProfile

class DnasqApp : Application() {
    val lab: LabProfile by lazy { LabProfile.fromFlavor(BuildConfig.LAB_FLAVOR) }
    val settings: LockSettings by lazy { LockSettings(this) }
    val templateStore: TemplateStore by lazy { TemplateStore(this) }
}
