package com.friday.assistant.tools

import android.content.Context
import com.friday.assistant.intelligence.MemoryManager
import com.friday.assistant.tools.apps.AppLauncherTool
import com.friday.assistant.tools.calendar.CalendarTool
import com.friday.assistant.tools.camera.CameraTool
import com.friday.assistant.tools.clipboard.ClipboardTool
import com.friday.assistant.tools.email.EmailTool
import com.friday.assistant.tools.files.FileManagerTool
import com.friday.assistant.tools.location.LocationTool
import com.friday.assistant.tools.media.MediaControlTool
import com.friday.assistant.tools.notes.NotesTool
import com.friday.assistant.tools.notes.RecallPreferenceTool
import com.friday.assistant.tools.notes.RememberPreferenceTool
import com.friday.assistant.tools.notifications.NotificationTool
import com.friday.assistant.tools.phone.PhoneTool
import com.friday.assistant.tools.search.WebSearchTool
import com.friday.assistant.tools.system.ScreenshotTool
import com.friday.assistant.tools.system.SystemControlsTool
import com.friday.assistant.tools.whatsapp.WhatsAppTool

object ToolRegistrar {
    fun registerAll(context: Context, memoryManager: MemoryManager) {
        ToolRegistry.register(SystemControlsTool(context))
        ToolRegistry.register(ScreenshotTool(context))
        ToolRegistry.register(PhoneTool(context))
        ToolRegistry.register(AppLauncherTool(context, memoryManager))
        ToolRegistry.register(MediaControlTool(context))
        ToolRegistry.register(WebSearchTool(context))
        ToolRegistry.register(ClipboardTool(context))
        ToolRegistry.register(NotesTool())
        ToolRegistry.register(CalendarTool(context))
        ToolRegistry.register(NotificationTool(context))
        ToolRegistry.register(LocationTool(context))
        ToolRegistry.register(CameraTool(context))
        ToolRegistry.register(FileManagerTool(context))
        ToolRegistry.register(RememberPreferenceTool(memoryManager))
        ToolRegistry.register(RecallPreferenceTool(memoryManager))
        ToolRegistry.register(WhatsAppTool(context, memoryManager))
        ToolRegistry.register(EmailTool(context, memoryManager))
    }
}
