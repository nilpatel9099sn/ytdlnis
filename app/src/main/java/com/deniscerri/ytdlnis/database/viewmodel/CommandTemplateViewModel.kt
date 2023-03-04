package com.deniscerri.ytdlnis.database.viewmodel

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context.CLIPBOARD_SERVICE
import android.widget.Toast
import androidx.core.content.ContextCompat.getSystemService
import androidx.lifecycle.*
import com.deniscerri.ytdlnis.database.DBManager
import com.deniscerri.ytdlnis.database.models.CommandTemplate
import com.deniscerri.ytdlnis.database.models.CommandTemplateExport
import com.deniscerri.ytdlnis.database.models.HistoryItem
import com.deniscerri.ytdlnis.database.models.TemplateShortcut
import com.deniscerri.ytdlnis.database.repository.CommandTemplateRepository
import com.deniscerri.ytdlnis.database.repository.HistoryRepository
import com.deniscerri.ytdlnis.database.repository.HistoryRepository.HistorySort
import com.deniscerri.ytdlnis.database.repository.HistoryRepository.HistorySortType
import com.deniscerri.ytdlnis.util.FileUtil
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CommandTemplateViewModel(private val application: Application) : AndroidViewModel(application) {
    private val repository: CommandTemplateRepository
    val items: LiveData<List<CommandTemplate>>
    val shortcuts : LiveData<List<TemplateShortcut>>
    private val jsonFormat = Json { prettyPrint = true }

    init {
        val dao = DBManager.getInstance(application).commandTemplateDao
        repository = CommandTemplateRepository(dao)
        items = repository.items
        shortcuts = repository.shortcuts
    }

    fun getTemplate(itemId: Long): CommandTemplate {
        return repository.getItem(itemId)
    }

    fun getAll(): List<CommandTemplate> {
        return repository.getAll()
    }

    fun insert(item: CommandTemplate) = viewModelScope.launch(Dispatchers.IO) {
        repository.insert(item)
    }

    fun delete(item: CommandTemplate) = viewModelScope.launch(Dispatchers.IO) {
        repository.delete(item)
    }

    fun insertShortcut(item: TemplateShortcut) = viewModelScope.launch(Dispatchers.IO) {
        repository.insertShortcut(item)
    }

    fun deleteShortcut(item: TemplateShortcut) = viewModelScope.launch(Dispatchers.IO) {
        repository.deleteShortcut(item)
    }

    fun deleteAll() = viewModelScope.launch(Dispatchers.IO) {
        repository.deleteAll()
    }

    fun update(item: CommandTemplate) = viewModelScope.launch(Dispatchers.IO) {
        repository.update(item)
    }

    suspend fun importFromClipboard() : Int {
        val allTemplates = repository.getAll()
        val allShortcuts = repository.getAllShortCuts()
        var count = 0
        val clipboard = application.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip!!.getItemAt(0).text.toString()
        try{
            jsonFormat.decodeFromString<CommandTemplateExport>(clip).run {
                templates.filterNot {
                    allTemplates.contains(it)
                }.run {
                    this.forEach {
                        repository.insert(it.copy(id=0))
                        count++
                    }
                }

                shortcuts.filterNot {
                    allShortcuts.contains(it)
                }.run{
                    this.forEach {
                        repository.insertShortcut(it.copy(id=0))
                        count++
                    }
                }
            }
        }catch (e: Exception){
            e.printStackTrace()
        }

        return count
    }


    fun exportToClipboard() = viewModelScope.launch(Dispatchers.IO) {
        val allTemplates = repository.getAll()
        val allShortcuts = repository.getAllShortCuts()
        val output = jsonFormat.encodeToString(
            CommandTemplateExport(
                    templates = allTemplates,
                    shortcuts = allShortcuts
                )
            )

        val clipboard: ClipboardManager =
            application.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setText(output)
    }

}