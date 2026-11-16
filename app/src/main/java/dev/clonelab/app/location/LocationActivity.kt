package dev.clonelab.app.location

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.android.material.snackbar.Snackbar
import dev.clonelab.app.R
import dev.clonelab.app.ServiceLocator
import dev.clonelab.app.databinding.ActivityLocationBinding
import dev.clonelab.app.model.CloneId
import dev.clonelab.app.model.LocationConfig
import kotlinx.coroutines.launch

/** Sets or clears the fake location of one clone. It is applied when the clone is launched. */
class LocationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLocationBinding
    private val viewModel: LocationViewModel by viewModels {
        viewModelFactory {
            val id = CloneId(
                intent.getStringExtra(EXTRA_PACKAGE).orEmpty(),
                intent.getIntExtra(EXTRA_USER_ID, 0),
            )
            initializer { LocationViewModel(id, ServiceLocator.repository(applicationContext)) }
        }
    }

    private var filled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLocationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // The fields restore their own text after rotation, so only fill them from the record once.
        filled = savedInstanceState?.getBoolean(STATE_FILLED) == true

        binding.save.setOnClickListener {
            binding.latitudeLayout.error = null
            binding.longitudeLayout.error = null
            viewModel.save(
                binding.enabled.isChecked,
                binding.latitude.text.toString(),
                binding.longitude.text.toString(),
            )
        }
        binding.stopNow.setOnClickListener { viewModel.stopNow() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.item.collect { item ->
                        if (item == null) return@collect
                        if (!filled) {
                            filled = true
                            fill(item.record.location)
                        }
                        binding.stopNow.visibility = if (item.running) View.VISIBLE else View.GONE
                    }
                }
                launch { viewModel.messages.collect(::onEvent) }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_FILLED, filled)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun fill(location: LocationConfig?) {
        binding.enabled.isChecked = location?.enabled == true
        if (location != null) {
            binding.latitude.setText(location.latitude.toString())
            binding.longitude.setText(location.longitude.toString())
        }
    }

    private fun onEvent(event: LocationEvent) {
        when (event) {
            LocationEvent.Saved -> Snackbar.make(binding.root, R.string.location_saved, Snackbar.LENGTH_SHORT).show()
            is LocationEvent.Invalid -> {
                val (layout, message) = when (event.problem) {
                    CoordinateProblem.EMPTY -> binding.latitudeLayout to R.string.location_error_empty
                    CoordinateProblem.NOT_A_NUMBER -> binding.latitudeLayout to R.string.location_error_number
                    CoordinateProblem.LATITUDE_RANGE -> binding.latitudeLayout to R.string.location_error_latitude
                    CoordinateProblem.LONGITUDE_RANGE -> binding.longitudeLayout to R.string.location_error_longitude
                    CoordinateProblem.ORIGIN -> binding.latitudeLayout to R.string.location_error_origin
                }
                layout.error = getString(message)
            }
        }
    }

    companion object {
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_USER_ID = "userId"
        private const val STATE_FILLED = "filled"

        fun intent(context: Context, id: CloneId) = Intent(context, LocationActivity::class.java)
            .putExtra(EXTRA_PACKAGE, id.packageName)
            .putExtra(EXTRA_USER_ID, id.userId)
    }
}
