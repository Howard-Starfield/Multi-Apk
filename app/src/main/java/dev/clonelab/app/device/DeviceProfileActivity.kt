package dev.clonelab.app.device

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import dev.clonelab.app.R
import dev.clonelab.app.ServiceLocator
import dev.clonelab.app.databinding.ActivityDeviceProfileBinding
import dev.clonelab.app.model.CloneId
import kotlinx.coroutines.launch

/** Picks the phone model and identifiers a clone reports. It is applied when the clone is launched. */
class DeviceProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceProfileBinding
    private val viewModel: DeviceProfileViewModel by viewModels {
        viewModelFactory {
            val id = CloneId(
                intent.getStringExtra(EXTRA_PACKAGE).orEmpty(),
                intent.getIntExtra(EXTRA_USER_ID, 0),
            )
            initializer { DeviceProfileViewModel(id, ServiceLocator.repository(applicationContext)) }
        }
    }

    /** Radio button ids by preset name; null is the "real device" button. */
    private val buttons = mutableMapOf<String?, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        addButton(null, getString(R.string.device_real))
        DeviceProfiles.presets.forEach { addButton(it.presetName, it.presetName.orEmpty()) }
        binding.models.setOnCheckedChangeListener { _, checkedId ->
            viewModel.select(buttons.entries.firstOrNull { it.value == checkedId }?.key)
        }

        bind(binding.androidId, IdentifierField.ANDROID_ID)
        bind(binding.imei, IdentifierField.IMEI)
        bind(binding.serial, IdentifierField.SERIAL)
        bind(binding.wifiMac, IdentifierField.WIFI_MAC)
        binding.randomise.setOnClickListener { viewModel.randomise() }
        binding.save.setOnClickListener {
            clearErrors()
            viewModel.save()
        }
        binding.stopNow.setOnClickListener { viewModel.stopNow() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.draft.collect { it?.let(::render) } }
                launch {
                    viewModel.item.collect {
                        binding.stopNow.visibility = if (it?.running == true) View.VISIBLE else View.GONE
                    }
                }
                launch { viewModel.messages.collect(::onEvent) }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun addButton(presetName: String?, label: String) {
        val button = RadioButton(this).apply {
            id = View.generateViewId()
            text = label
        }
        buttons[presetName] = button.id
        binding.models.addView(button, RadioGroup.LayoutParams(RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun bind(editText: TextInputEditText, field: IdentifierField) {
        editText.doAfterTextChanged { viewModel.edit(field, it?.toString().orEmpty()) }
    }

    private fun render(draft: DeviceDraft) {
        buttons[draft.presetName]?.let { if (binding.models.checkedRadioButtonId != it) binding.models.check(it) }
        binding.identifiers.visibility = if (draft.presetName == null) View.GONE else View.VISIBLE
        setText(binding.androidId, draft.androidId)
        setText(binding.imei, draft.imei)
        setText(binding.serial, draft.serial)
        setText(binding.wifiMac, draft.wifiMac)
    }

    /** Skips equal text so typing does not reset the cursor. */
    private fun setText(editText: TextInputEditText, text: String) {
        if (editText.text?.toString() != text) editText.setText(text)
    }

    private fun clearErrors() {
        binding.androidIdLayout.error = null
        binding.imeiLayout.error = null
        binding.serialLayout.error = null
        binding.wifiMacLayout.error = null
    }

    private fun onEvent(event: DeviceEvent) {
        when (event) {
            is DeviceEvent.Failed -> Snackbar.make(binding.root, event.message.resolve(this), Snackbar.LENGTH_LONG).show()
            DeviceEvent.Saved -> Snackbar.make(binding.root, R.string.device_saved, Snackbar.LENGTH_SHORT).show()
            is DeviceEvent.Invalid -> event.fields.forEach {
                when (it) {
                    IdentifierField.ANDROID_ID -> binding.androidIdLayout.error = getString(R.string.device_error_android_id)
                    IdentifierField.IMEI -> binding.imeiLayout.error = getString(R.string.device_error_imei)
                    IdentifierField.SERIAL -> binding.serialLayout.error = getString(R.string.device_error_serial)
                    IdentifierField.WIFI_MAC -> binding.wifiMacLayout.error = getString(R.string.device_error_wifi_mac)
                }
            }
        }
    }

    companion object {
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_USER_ID = "userId"

        fun intent(context: Context, id: CloneId) = Intent(context, DeviceProfileActivity::class.java)
            .putExtra(EXTRA_PACKAGE, id.packageName)
            .putExtra(EXTRA_USER_ID, id.userId)
    }
}
