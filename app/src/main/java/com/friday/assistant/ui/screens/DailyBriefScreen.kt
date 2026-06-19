package com.friday.assistant.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.friday.assistant.core.FridayApplication
import com.friday.assistant.core.db.BriefItemEntity
import com.friday.assistant.core.db.InterestEntity
import com.friday.assistant.intelligence.brief.BriefScheduler
import com.friday.assistant.ui.theme.*
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyBriefScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val dao = remember { FridayApplication.database.dao() }

    // Collect flow states from DB
    val interests by dao.getAllInterestsFlow().collectAsState(initial = emptyList())
    val briefItems by dao.getNewBriefItemsFlow().collectAsState(initial = emptyList())

    var selectedInterestId by remember { mutableStateOf("all") }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }

    val filteredItems = remember(briefItems, selectedInterestId) {
        if (selectedInterestId == "all") {
            briefItems
        } else {
            briefItems.filter { it.interestId == selectedInterestId }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianDark)
            .padding(horizontal = 16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Newspaper,
                    contentDescription = null,
                    tint = NeonBlue,
                    modifier = Modifier
                        .size(36.dp)
                        .background(NeonBlue.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                        .padding(6.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Daily Briefing",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Row {
                IconButton(
                    onClick = {
                        isRefreshing = true
                        coroutineScope.launch {
                            BriefScheduler.triggerOneTimeCrawl(context)
                            Toast.makeText(context, "Crawler triggered in background...", Toast.LENGTH_SHORT).show()
                            kotlinx.coroutines.delay(2000)
                            isRefreshing = false
                        }
                    },
                    modifier = Modifier.background(SlateGray, CircleShape)
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = NeonCyan, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "Sync", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = { showSettingsDialog = true },
                    modifier = Modifier.background(SlateGray, CircleShape)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Manage Interests", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }

        // Horizontally scrolling interest chips
        val enabledInterests = remember(interests) { interests.filter { it.isEnabled } }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            InterestChip(
                label = "All",
                isSelected = selectedInterestId == "all",
                onClick = { selectedInterestId = "all" }
            )
            for (interest in enabledInterests) {
                InterestChip(
                    label = interest.title,
                    isSelected = selectedInterestId == interest.id,
                    onClick = { selectedInterestId = interest.id }
                )
            }
        }

        // Feed list
        if (filteredItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Inbox,
                        contentDescription = "Empty Feed",
                        tint = SilverText.copy(alpha = 0.4f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No unread news briefing items",
                        color = SilverText,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Press refresh or wait for the automatic crawler",
                        color = SilverText.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filteredItems, key = { it.id }) { item ->
                    BriefCard(
                        item = item,
                        onDismiss = {
                            coroutineScope.launch {
                                dao.updateBriefItemStatus(item.id, "dismissed")
                            }
                        },
                        onArchive = {
                            coroutineScope.launch {
                                dao.updateBriefItemStatus(item.id, "archived")
                                Toast.makeText(context, "Saved to archives", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        }
    }

    if (showSettingsDialog) {
        ManageInterestsDialog(
            interests = interests,
            onDismiss = { showSettingsDialog = false },
            onToggle = { interest, isEnabled ->
                coroutineScope.launch {
                    dao.updateInterest(interest.copy(isEnabled = isEnabled))
                }
            },
            onAdd = { title, category, keywords, sources ->
                coroutineScope.launch {
                    val id = "custom_" + title.trim().lowercase().replace("\\s+".toRegex(), "_")
                    val interest = InterestEntity(
                        id = id,
                        title = title,
                        category = category,
                        keywordsJson = Gson().toJson(keywords),
                        sourcesJson = Gson().toJson(sources),
                        isEnabled = true,
                        isCustom = true
                    )
                    dao.insertInterest(interest)
                }
            },
            onDelete = { interest ->
                coroutineScope.launch {
                    dao.deleteInterest(interest)
                }
            }
        )
    }
}

@Composable
fun InterestChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) NeonBlue else SlateGray,
        border = if (isSelected) null else BorderStroke(1.dp, CyberBorder),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = Color.White,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun BriefCard(
    item: BriefItemEntity,
    onDismiss: () -> Unit,
    onArchive: () -> Unit
) {
    val context = LocalContext.current
    val timeStr = remember(item.pubDate) {
        val df = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        df.format(Date(item.pubDate))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(width = 1.dp, color = CyberBorder, shape = RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = GlassObsidian)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Source Name, Category Tag & Date
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = NeonBlue.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, NeonBlue.copy(alpha = 0.3f))
                ) {
                    Text(
                        text = item.sourceName,
                        color = NeonCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Text(
                    text = timeStr,
                    color = SilverText,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Article Title
            Text(
                text = item.title,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Summary
            Text(
                text = item.summary,
                color = SilverText,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Source Link & Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Source Link Text Button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(item.url)).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast
                                    .makeText(context, "Could not open link", Toast.LENGTH_SHORT)
                                    .show()
                            }
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Launch,
                        contentDescription = null,
                        tint = NeonCyan,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Source Link",
                        color = NeonCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Archive & Dismiss actions
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onArchive,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bookmark,
                            contentDescription = "Archive",
                            tint = SilverText,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Dismiss",
                            tint = AlertRed.copy(alpha = 0.8f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ManageInterestsDialog(
    interests: List<InterestEntity>,
    onDismiss: () -> Unit,
    onToggle: (InterestEntity, Boolean) -> Unit,
    onAdd: (String, String, List<String>, List<String>) -> Unit,
    onDelete: (InterestEntity) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("news") }
    var keywordsInput by remember { mutableStateOf("") }
    var sourcesInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Manage Daily Brief Interests", color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // List existing
                Text(text = "Active Topics", color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (interest in interests) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SlateGray.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = interest.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Text(text = "Category: ${interest.category}", color = SilverText, fontSize = 11.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = interest.isEnabled,
                                    onCheckedChange = { onToggle(interest, it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = NeonCyan,
                                        checkedTrackColor = NeonBlue.copy(alpha = 0.5f)
                                    )
                                )
                                if (interest.isCustom) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    IconButton(
                                        onClick = { onDelete(interest) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = AlertRed, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = CyberBorder, thickness = 1.dp)

                // Add Custom Section
                Text(text = "Add Custom Topic", color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Topic Title (e.g. AI Startups)", color = SilverText) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = NeonBlue,
                        unfocusedBorderColor = CyberBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category (news/sports/event)", color = SilverText) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = NeonBlue,
                        unfocusedBorderColor = CyberBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = keywordsInput,
                    onValueChange = { keywordsInput = it },
                    label = { Text("Keywords (comma separated)", color = SilverText) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = NeonBlue,
                        unfocusedBorderColor = CyberBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = sourcesInput,
                    onValueChange = { sourcesInput = it },
                    label = { Text("Sources RSS/HTML URL (comma separated)", color = SilverText) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = NeonBlue,
                        unfocusedBorderColor = CyberBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank() && keywordsInput.isNotBlank() && sourcesInput.isNotBlank()) {
                        val keywords = keywordsInput.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        val sources = sourcesInput.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        if (keywords.isNotEmpty() && sources.isNotEmpty()) {
                            onAdd(title, category, keywords, sources)
                            // reset
                            title = ""
                            keywordsInput = ""
                            sourcesInput = ""
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = NeonBlue)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add Topic")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = SilverText)
            }
        },
        containerColor = GlassObsidian,
        shape = RoundedCornerShape(16.dp)
    )
}
