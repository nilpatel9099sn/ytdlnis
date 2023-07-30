package com.deniscerri.ytdlnis.ui.downloadcard

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.text.format.DateFormat
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.get
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.deniscerri.ytdlnis.R
import com.deniscerri.ytdlnis.adapter.ConfigureMultipleDownloadsAdapter
import com.deniscerri.ytdlnis.database.models.DownloadItem
import com.deniscerri.ytdlnis.database.models.Format
import com.deniscerri.ytdlnis.database.models.ResultItem
import com.deniscerri.ytdlnis.database.repository.DownloadRepository
import com.deniscerri.ytdlnis.database.viewmodel.CommandTemplateViewModel
import com.deniscerri.ytdlnis.database.viewmodel.DownloadViewModel
import com.deniscerri.ytdlnis.database.viewmodel.ResultViewModel
import com.deniscerri.ytdlnis.receiver.ShareActivity
import com.deniscerri.ytdlnis.util.FileUtil
import com.deniscerri.ytdlnis.util.UiUtil
import com.google.android.material.bottomappbar.BottomAppBar
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import it.xabaras.android.recyclerview.swipedecorator.RecyclerViewSwipeDecorator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

class DownloadMultipleBottomSheetDialog(private var results: List<ResultItem?>, private var items: MutableList<DownloadItem>) : BottomSheetDialogFragment(), ConfigureMultipleDownloadsAdapter.OnItemClickListener, View.OnClickListener,
    ConfigureDownloadBottomSheetDialog.OnDownloadItemUpdateListener {
    private lateinit var downloadViewModel: DownloadViewModel
    private lateinit var commandTemplateViewModel: CommandTemplateViewModel
    private lateinit var resultViewModel: ResultViewModel
    private lateinit var listAdapter : ConfigureMultipleDownloadsAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var behavior: BottomSheetBehavior<View>
    private lateinit var bottomAppBar: BottomAppBar
    private lateinit var sharedPreferences: SharedPreferences


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]
        resultViewModel = ViewModelProvider(this)[ResultViewModel::class.java]
        commandTemplateViewModel = ViewModelProvider(this)[CommandTemplateViewModel::class.java]
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
    }

    @SuppressLint("RestrictedApi", "NotifyDataSetChanged")
    override fun setupDialog(dialog: Dialog, style: Int) {
        super.setupDialog(dialog, style)
        val view = LayoutInflater.from(context).inflate(R.layout.download_multiple_bottom_sheet, null)
        dialog.setContentView(view)

        if (items.isEmpty()) dismiss()

        dialog.setOnShowListener {
            behavior = BottomSheetBehavior.from(view.parent as View)
            val displayMetrics = DisplayMetrics()
            requireActivity().windowManager.defaultDisplay.getMetrics(displayMetrics)
            if(resources.getBoolean(R.bool.isTablet)){
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }

        listAdapter =
            ConfigureMultipleDownloadsAdapter(
                this,
                requireActivity()
            )

        recyclerView = view.findViewById(R.id.downloadMultipleRecyclerview)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = listAdapter
        val itemTouchHelper = ItemTouchHelper(simpleCallback)
        itemTouchHelper.attachToRecyclerView(recyclerView)
        listAdapter.submitList(items.toList())

        val scheduleBtn = view.findViewById<MaterialButton>(R.id.bottomsheet_schedule_button)
        val download = view.findViewById<Button>(R.id.bottomsheet_download_button)


        scheduleBtn.setOnClickListener{
            UiUtil.showDatePicker(parentFragmentManager) {
                scheduleBtn.isEnabled = false
                download.isEnabled = false
                items.forEach { item ->
                    item.downloadStartTime = it.timeInMillis
                }
                runBlocking {
                    downloadViewModel.queueDownloads(items)
                    val first = items.first()
                    val date = SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "ddMMMyyyy - HHmm"), Locale.getDefault()).format(first.downloadStartTime)
                    Toast.makeText(context, getString(R.string.download_rescheduled_to) + " " + date, Toast.LENGTH_LONG).show()
                }
                dismiss()
            }
        }

        download!!.setOnClickListener {
            scheduleBtn.isEnabled = false
            download.isEnabled = false
            runBlocking {
                downloadViewModel.queueDownloads(items)
            }
            dismiss()
        }

        download.setOnLongClickListener {
            val dd = MaterialAlertDialogBuilder(requireContext())
            dd.setTitle(getString(R.string.save_for_later))
            dd.setNegativeButton(getString(R.string.cancel)) { dialogInterface: DialogInterface, _: Int -> dialogInterface.cancel() }
            dd.setPositiveButton(getString(R.string.ok)) { _: DialogInterface?, _: Int ->
                lifecycleScope.launch{
                    items.map { it.status = DownloadRepository.Status.Saved.toString() }
                    withContext(Dispatchers.IO){
                        downloadViewModel.insertAll(items)
                    }
                    dismiss()
                }
            }
            dd.show()
            true
        }

        bottomAppBar = view.findViewById(R.id.bottomAppBar)
        val preferredDownloadType = bottomAppBar.menu.findItem(R.id.preferred_download_type)
        when(items.first().type){
            DownloadViewModel.Type.audio -> {
                preferredDownloadType.setIcon(R.drawable.baseline_audio_file_24)
            }
            DownloadViewModel.Type.video -> {
                preferredDownloadType.setIcon(R.drawable.baseline_video_file_24)

            }
            DownloadViewModel.Type.command -> {
                preferredDownloadType.setIcon(R.drawable.baseline_insert_drive_file_24)
            }
        }

        val formatListener = object : OnFormatClickListener {
            override fun onFormatClick(allFormats: List<List<Format>>, selectedFormats: List<FormatTuple>) {
                val formatCollection = mutableListOf<List<Format>>()
                allFormats.forEach {f ->
                    formatCollection.add(f.mapTo(mutableListOf()) {it.copy()})
                }
                items.forEachIndexed { index, i ->
                    i.allFormats.clear()
                    if (formatCollection.size == items.size && formatCollection[index].isNotEmpty()) {
                        runCatching {
                            i.allFormats.addAll(formatCollection[index])
                        }
                    }
                    i.format = selectedFormats[index].format
                    if (i.type == DownloadViewModel.Type.video) selectedFormats[index].audioFormats?.map { it.format_id }?.let { i.videoPreferences.audioFormatIDs.addAll(it) }
                }
                listAdapter.submitList(items.toList())
                listAdapter.notifyDataSetChanged()
            }
        }

        bottomAppBar.setOnMenuItemClickListener { m: MenuItem ->
            when (m.itemId) {
                R.id.folder -> {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    pathResultLauncher.launch(intent)
                }
                R.id.preferred_download_type -> {
                    lifecycleScope.launch{
                        val bottomSheet = BottomSheetDialog(requireContext())
                        bottomSheet.requestWindowFeature(Window.FEATURE_NO_TITLE)
                        bottomSheet.setContentView(R.layout.download_type_sheet)

                        // BUTTON ----------------------------------
                        val audio = bottomSheet.findViewById<TextView>(R.id.audio)
                        val video = bottomSheet.findViewById<TextView>(R.id.video)
                        val command = bottomSheet.findViewById<TextView>(R.id.command)


                        withContext(Dispatchers.IO){
                            val nr = commandTemplateViewModel.getTotalNumber()
                            if(nr == 0){
                                command!!.visibility = View.GONE
                            }else{
                                command!!.visibility = View.VISIBLE
                            }
                        }

                        audio!!.setOnClickListener {
                            items = downloadViewModel.switchDownloadType(items, DownloadViewModel.Type.audio).toMutableList()
                            listAdapter.submitList(items.toList())
                            listAdapter.notifyDataSetChanged()
                            preferredDownloadType.setIcon(R.drawable.baseline_audio_file_24)
                            bottomAppBar.menu[1].icon?.alpha = 255
                            bottomSheet.cancel()
                        }

                        video!!.setOnClickListener {
                            items = downloadViewModel.switchDownloadType(items, DownloadViewModel.Type.video).toMutableList()
                            listAdapter.submitList(items.toList())
                            listAdapter.notifyDataSetChanged()
                            preferredDownloadType.setIcon(R.drawable.baseline_video_file_24)
                            bottomAppBar.menu[1].icon?.alpha = 255
                            bottomSheet.cancel()
                        }

                        command!!.setOnClickListener {
                            lifecycleScope.launch {
                                items = withContext(Dispatchers.IO){
                                    downloadViewModel.switchDownloadType(items, DownloadViewModel.Type.command).toMutableList()
                                }
                                listAdapter.submitList(items.toList())
                                listAdapter.notifyDataSetChanged()
                                preferredDownloadType.setIcon(R.drawable.baseline_insert_drive_file_24)
                                bottomAppBar.menu[1].icon?.alpha = 255
                                bottomSheet.cancel()
                            }
                        }

                        bottomSheet.show()
                        val displayMetrics = DisplayMetrics()
                        requireActivity().windowManager.defaultDisplay.getMetrics(displayMetrics)
                        bottomSheet.behavior.peekHeight = displayMetrics.heightPixels
                        bottomSheet.window!!.setLayout(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }

                }
                R.id.filename_template -> {
                    if (items.count { it.type == DownloadViewModel.Type.command } != 0){
                        Toast.makeText(requireContext(), getString(R.string.format_filtering_hint_2), Toast.LENGTH_SHORT).show()
                    }else{
                        val builder = MaterialAlertDialogBuilder(requireContext())
                        builder.setTitle(getString(R.string.file_name_template))
                        val inputLayout = layoutInflater.inflate(R.layout.textinput, null)
                        val editText = inputLayout.findViewById<EditText>(R.id.url_edittext)
                        inputLayout.findViewById<TextInputLayout>(R.id.url_textinput).hint = getString(R.string.file_name_template)
                        editText.setSelection(editText.text.length)
                        builder.setView(inputLayout)
                        builder.setPositiveButton(
                            getString(R.string.ok)
                        ) { dialog: DialogInterface?, which: Int ->

                            lifecycleScope.launch {
                                items.forEach {
                                    it.customFileNameTemplate = editText.text.toString()
                                }
                                listAdapter.submitList(items.toList())
                                listAdapter.notifyDataSetChanged()
                            }

                        }

                        // handle the negative button of the alert dialog
                        builder.setNegativeButton(
                            getString(R.string.cancel)
                        ) { dialog: DialogInterface?, which: Int -> }

                        val filenameTemplateDialog = builder.create()
                        editText.doOnTextChanged { text, start, before, count ->
                            filenameTemplateDialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = editText.text.isNotEmpty()
                        }
                        filenameTemplateDialog.show()
                        val imm = context?.getSystemService(AppCompatActivity.INPUT_METHOD_SERVICE) as InputMethodManager
                        editText!!.postDelayed({
                            editText.requestFocus()
                            imm.showSoftInput(editText, 0)
                        }, 300)
                        filenameTemplateDialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = editText.text.isNotEmpty()
                        filenameTemplateDialog.getButton(AlertDialog.BUTTON_NEUTRAL).gravity = Gravity.START
                    }


                }
                R.id.format -> {
                    if (! items.all { it.type == items[0].type }){
                        Toast.makeText(requireContext(), getString(R.string.format_filtering_hint), Toast.LENGTH_SHORT).show()
                    }else{
                        if (items.first().type == DownloadViewModel.Type.command){
                            lifecycleScope.launch {
                                UiUtil.showCommandTemplates(requireActivity(), commandTemplateViewModel) {
                                    val format = downloadViewModel.generateCommandFormat(it)
                                    items.forEach { f ->
                                        f.format = format
                                    }
                                    listAdapter.submitList(items.toList())
                                    listAdapter.notifyDataSetChanged()
                                }
                            }
                        }else{
                            lifecycleScope.launch {
                                val flatFormatCollection = items.map { it.allFormats }.flatten()
                                val commonFormats = withContext(Dispatchers.IO){
                                    flatFormatCollection.groupingBy { it.format_id }.eachCount().filter { it.value == items.size }.mapValues { flatFormatCollection.first { f -> f.format_id == it.key } }.map { it.value }
                                }

                                val formats = if (commonFormats.isNotEmpty() && items.none{it.allFormats.isEmpty()}) {
                                    items.map { it.allFormats }
                                }else{
                                    when(items.first().type){
                                        DownloadViewModel.Type.audio -> listOf<List<Format>>(downloadViewModel.getGenericAudioFormats())
                                        else -> listOf<List<Format>>(downloadViewModel.getGenericVideoFormats())
                                    }
                                }
                                val bottomSheet = FormatSelectionBottomSheetDialog(items, formats, formatListener)
                                bottomSheet.show(parentFragmentManager, "formatSheet")
                            }
                        }
                    }

                }
            }
            true
        }

    }

    private var pathResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let {
                activity?.contentResolver?.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            items.forEach {
                it.downloadPath = result.data?.data.toString()
            }
            Toast.makeText(requireContext(), "Changed every item's download path to: ${FileUtil.formatPath(result.data!!.data.toString())}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        cleanup()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        cleanup()
    }

    private fun cleanup(){
        kotlin.runCatching {
            parentFragmentManager.beginTransaction().remove(parentFragmentManager.findFragmentByTag("downloadMultipleSheet")!!).commit()
            if (parentFragmentManager.fragments.size == 1){
                (activity as ShareActivity).finish()
            }
        }
    }

    override fun onButtonClick(itemURL: String) {
        lifecycleScope.launch {
            val item = items.find { it.url == itemURL } ?: return@launch
            val position = items.indexOf(item)

            val bottomSheet = BottomSheetDialog(requireContext())
            bottomSheet.requestWindowFeature(Window.FEATURE_NO_TITLE)
            bottomSheet.setContentView(R.layout.download_type_sheet)

            // BUTTON ----------------------------------
            val audio = bottomSheet.findViewById<TextView>(R.id.audio)
            val video = bottomSheet.findViewById<TextView>(R.id.video)
            val command = bottomSheet.findViewById<TextView>(R.id.command)

            withContext(Dispatchers.IO){
                val nr = commandTemplateViewModel.getTotalNumber()
                if(nr == 0){
                    command!!.visibility = View.GONE
                }else{
                    command!!.visibility = View.VISIBLE
                }
            }

            audio!!.setOnClickListener {
                items[position] = downloadViewModel.switchDownloadType(listOf(item), DownloadViewModel.Type.audio).first()
                listAdapter.submitList(items.toList())
                listAdapter.notifyItemChanged(position)
                bottomSheet.cancel()
            }

            video!!.setOnClickListener {
                items[position] = downloadViewModel.switchDownloadType(listOf(item), DownloadViewModel.Type.video).first()
                listAdapter.submitList(items.toList())
                listAdapter.notifyItemChanged(position)
                bottomSheet.cancel()
            }

            command!!.setOnClickListener {
                lifecycleScope.launch {
                    items[position] = withContext(Dispatchers.IO){
                        downloadViewModel.switchDownloadType(listOf(item), DownloadViewModel.Type.command).first()
                    }
                    listAdapter.submitList(items.toList())
                    listAdapter.notifyItemChanged(position)
                    bottomSheet.cancel()
                }
            }

            bottomSheet.show()
            val displayMetrics = DisplayMetrics()
            requireActivity().windowManager.defaultDisplay.getMetrics(displayMetrics)
            bottomSheet.behavior.peekHeight = displayMetrics.heightPixels
            bottomSheet.window!!.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    override fun onCardClick(itemURL: String) {
        lifecycleScope.launch{
            val downloadItem = items.find { it.url == itemURL }
            val resultItem = withContext(Dispatchers.IO){
                resultViewModel.getItemByURL(downloadItem!!.url)
            }
            if (parentFragmentManager.findFragmentByTag("configureDownloadSingleSheet") == null){
                val bottomSheet = ConfigureDownloadBottomSheetDialog(resultItem, downloadItem!!, this@DownloadMultipleBottomSheetDialog)
                bottomSheet.show(parentFragmentManager, "configureDownloadSingleSheet")
            }
        }
    }

    override fun onClick(p0: View?) {
        TODO("Not yet implemented")
    }

    override fun onDownloadItemUpdate(resultItemID: Long, item: DownloadItem) {
        val i = items.indexOf(items.find { it.url == item.url })
        items[i] = item
        if (! items.all { it.type == items[0].type }){
            bottomAppBar.menu[1].icon?.alpha = 30
        }else{
            bottomAppBar.menu[1].icon?.alpha = 255
        }

        if (items.count { it.type == DownloadViewModel.Type.command } == 0){
            bottomAppBar.menu[2].icon?.alpha = 255
        }else{
            bottomAppBar.menu[2].icon?.alpha = 30
        }

        listAdapter.submitList(items.toList())
    }

    private var simpleCallback: ItemTouchHelper.SimpleCallback =
        object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(recyclerView: RecyclerView,viewHolder: RecyclerView.ViewHolder,target: RecyclerView.ViewHolder
            ): Boolean {
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                when (direction) {
                    ItemTouchHelper.LEFT -> {
                        val deletedItem = items[position]
                        items.remove(deletedItem)
                        listAdapter.submitList(items.toList())
                        if (items.isNotEmpty()){
                            Snackbar.make(recyclerView, getString(R.string.you_are_going_to_delete) + ": " + deletedItem.title, Snackbar.LENGTH_LONG)
                                .setAction(getString(R.string.undo)) {
                                    items.add(position, deletedItem)
                                    listAdapter.submitList(items.toList())
                                }.show()
                        }else{
                            dismiss()
                        }

                    }

                }
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                RecyclerViewSwipeDecorator.Builder(
                    requireContext(),
                    c,
                    recyclerView,
                    viewHolder,
                    dX,
                    dY,
                    actionState,
                    isCurrentlyActive
                )
                    .addSwipeLeftBackgroundColor(Color.RED)
                    .addSwipeLeftActionIcon(R.drawable.baseline_delete_24)
                    .addSwipeRightBackgroundColor(
                        MaterialColors.getColor(
                            requireContext(),
                            R.attr.colorOnSurfaceInverse, Color.TRANSPARENT
                        )
                    )
                    .create()
                    .decorate()
                super.onChildDraw(
                    c,
                    recyclerView,
                    viewHolder!!,
                    dX,
                    dY,
                    actionState,
                    isCurrentlyActive
                )
            }
        }

}

