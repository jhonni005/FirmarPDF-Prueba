package com.zonadev.firmarpdf.ui.home

import android.graphics.Bitmap
import android.text.format.DateFormat
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zonadev.firmarpdf.R
import com.zonadev.firmarpdf.data.recentpdf.RecentPdfEntity
import java.util.Date

@Composable
internal fun RecentPdfRow(pdf: RecentPdfEntity, load: suspend (RecentPdfEntity) -> Bitmap?, location: suspend (String) -> String?, onOpen: () -> Unit) {
    val thumbnail by produceState<Bitmap?>(null, pdf) { value = load(pdf) }
    val context = LocalContext.current
    val place by produceState<String?>(null, pdf.uri) { value = location(pdf.uri) }
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(64.dp, 88.dp), contentAlignment = Alignment.Center) {
                thumbnail?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    ?: Icon(painterResource(R.drawable.ic_pdf), null, Modifier.size(40.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(pdf.displayName.ifBlank { stringResource(R.string.pdf_without_name) }, style = MaterialTheme.typography.titleMedium)
                Text(pdf.documentModifiedAt?.let { timestamp ->
                    val date = Date(timestamp)
                    stringResource(R.string.recent_modified, DateFormat.getDateFormat(context).format(date) + " " + DateFormat.getTimeFormat(context).format(date))
                } ?: stringResource(R.string.recent_modified_unknown), style = MaterialTheme.typography.bodySmall)
                if (pdf.unavailable) Text(stringResource(R.string.recent_unavailable), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(place ?: stringResource(R.string.recent_location_unknown),
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
    }
}
