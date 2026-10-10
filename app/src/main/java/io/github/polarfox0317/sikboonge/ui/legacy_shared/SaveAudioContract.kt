package io.github.polarfox0317.sikboonge

import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

class SaveAudioContract : ActivityResultContract<AudioResult, Uri?>() {
    override fun createIntent(context: android.content.Context, input: AudioResult) = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(input.mime).putExtra(Intent.EXTRA_TITLE, input.name)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
}
