package dev.clonelab.app.tools.export

import android.content.pm.PackageManager
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dev.clonelab.app.R
import dev.clonelab.app.databinding.SheetExportBinding
import dev.clonelab.app.tools.PhoneApp

/**
 * Details of one app before exporting it: version, size, file count, and a note when it is a split app.
 * Export sends [RESULT_KEY] with the package name to the activity, which opens the file picker.
 */
class ExportSheet : BottomSheetDialogFragment() {

    private var _binding: SheetExportBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = SheetExportBinding.inflate(inflater, container, false)
        _binding = binding
        val args = requireArguments()
        val pkg = args.getString(ARG_PACKAGE).orEmpty()
        val files = args.getInt(ARG_FILES)
        val version = args.getString(ARG_VERSION).orEmpty()

        binding.label.text = args.getString(ARG_LABEL)
        binding.packageName.text = pkg
        binding.version.text =
            if (version.isBlank()) getString(R.string.tools_export_no_version) else getString(R.string.tools_export_version, version)
        binding.sizeFiles.text = getString(
            R.string.tools_export_size_files,
            Formatter.formatFileSize(requireContext(), args.getLong(ARG_BYTES)),
            resources.getQuantityString(R.plurals.tools_export_files, files, files),
        )
        binding.splitNote.visibility = if (files > 1) View.VISIBLE else View.GONE
        binding.icon.setImageDrawable(
            try {
                requireContext().packageManager.getApplicationIcon(pkg)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            },
        )
        binding.export.setOnClickListener {
            parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf(ARG_PACKAGE to pkg))
            dismiss()
        }
        return binding.root
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "export_sheet"
        const val RESULT_KEY = "export_confirmed"
        const val ARG_PACKAGE = "package"
        private const val ARG_LABEL = "label"
        private const val ARG_VERSION = "version"
        private const val ARG_BYTES = "bytes"
        private const val ARG_FILES = "files"

        fun forApp(app: PhoneApp) = ExportSheet().apply {
            arguments = bundleOf(
                ARG_PACKAGE to app.packageName,
                ARG_LABEL to app.label,
                ARG_VERSION to app.versionName,
                ARG_BYTES to app.apkBytes,
                ARG_FILES to app.apkPaths.size,
            )
        }
    }
}
