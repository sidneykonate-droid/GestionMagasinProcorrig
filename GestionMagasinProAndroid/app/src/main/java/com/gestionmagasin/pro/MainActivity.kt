package com.gestionmagasin.pro

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gestionmagasin.pro.data.ArticleEntity
import com.gestionmagasin.pro.utils.BarcodeAndPrintHelper
import com.gestionmagasin.pro.utils.ImageAndVisualSearchHelper
import com.gestionmagasin.pro.utils.OcrAndLabelParser
import com.gestionmagasin.pro.viewmodel.MagasinViewModel
import java.io.File
import java.util.Locale

private val OrcaObsidian = Color(0xFF0F172A)
private val OrcaSlate = Color(0xFF1E293B)
private val OrcaIndigo = Color(0xFF3730A3)
private val OrcaGold = Color(0xFFD97706)
private val OrcaEmerald = Color(0xFF059669)
private val OrcaCrimson = Color(0xFFDC2626)
private val OrcaSurfaceBg = Color(0xFFF1F5F9)
private val OrcaCardBorder = Color(0xFFE2E8F0)

private val OrcaLightColorScheme = lightColorScheme(
    primary = OrcaIndigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = OrcaSlate,
    onSecondary = Color.White,
    tertiary = OrcaGold,
    background = OrcaSurfaceBg,
    surface = Color.White,
    onSurface = OrcaObsidian,
    outlineVariant = OrcaCardBorder
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = OrcaLightColorScheme) {
                GestionMagasinApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GestionMagasinApp(vm: MagasinViewModel = viewModel()) {
    val context = LocalContext.current
    var currentTab by remember { mutableIntStateOf(0) }
    val articles by vm.allArticles.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val cameraPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        cameraPermLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(vm.requestedTab) {
        if (vm.requestedTab >= 0) {
            currentTab = vm.requestedTab
            vm.requestedTab = -1
        }
    }

    LaunchedEffect(vm.statusMessage) {
        vm.statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            vm.statusMessage = null
        }
    }

    var pendingVisualSearchUri by remember { mutableStateOf<Uri?>(null) }
    val visualCameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok && pendingVisualSearchUri != null) vm.searchArticleByPhoto(pendingVisualSearchUri!!)
    }
    val visualGalleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.searchArticleByPhoto(uri)
    }

    Scaffold(
        containerColor = OrcaSurfaceBg,
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    shape = RoundedCornerShape(12.dp),
                    containerColor = OrcaObsidian,
                    contentColor = Color.White
                )
            }
        },
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(OrcaObsidian, OrcaSlate, Color(0xFF1E1B4B))
                        )
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White,
                            shadowElevation = 4.dp,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.logo_orca_deco),
                                contentDescription = "Logo ORCA DECO",
                                modifier = Modifier.padding(4.dp),
                                contentScale = ContentScale.Fit
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "GestionMagasin Pro",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 18.sp,
                                color = Color.White
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF10B981))
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "${articles.size} article(s) • Modèle ORCA DECO",
                                    fontSize = 11.sp,
                                    color = Color(0xFFCBD5E1)
                                )
                            }
                        }
                    }

                    FilledTonalIconButton(
                        onClick = {
                            val uri = ImageAndVisualSearchHelper.createTempCameraUri(context)
                            pendingVisualSearchUri = uri
                            visualCameraLauncher.launch(uri)
                        },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color.White.copy(alpha = 0.14f),
                            contentColor = Color(0xFFFBBF24)
                        )
                    ) {
                        Icon(Icons.Rounded.ImageSearch, contentDescription = "Rechercher par photo")
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = Color.White,
                tonalElevation = 8.dp
            ) {
                val navItems = listOf(
                    Triple(0, if (vm.isEditing) "Modifier" else "Créer Art.", if (vm.isEditing) Icons.Rounded.Edit else Icons.Rounded.AddCircleOutline),
                    Triple(1, "Base", Icons.Rounded.Inventory2),
                    Triple(2, "Étiquettes", Icons.Rounded.Print),
                    Triple(3, "Sauvegarde", Icons.Rounded.CloudSync)
                )
                navItems.forEach { (index, label, icon) ->
                    NavigationBarItem(
                        selected = currentTab == index,
                        onClick = { currentTab = index },
                        icon = {
                            if (index == 2) {
                                BadgedBox(badge = {
                                    val total = vm.labelPrintQueue.sumOf { it.quantity }
                                    if (total > 0) {
                                        Badge(containerColor = OrcaGold, contentColor = Color.White) {
                                            Text("$total", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }) {
                                    Icon(icon, contentDescription = label)
                                }
                            } else {
                                Icon(icon, contentDescription = label)
                            }
                        },
                        label = {
                            Text(
                                label,
                                fontSize = 11.sp,
                                fontWeight = if (currentTab == index) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = OrcaIndigo,
                            selectedTextColor = OrcaIndigo,
                            indicatorColor = Color(0xFFE0E7FF)
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (currentTab) {
                0 -> CreateOrEditArticleScreen(vm)
                1 -> DatabaseScreen(vm, articles, onNavigateToLabels = { currentTab = 2 })
                2 -> LabelsPrintScreen(
                    vm = vm,
                    onSearchByPhotoCamera = {
                        val uri = ImageAndVisualSearchHelper.createTempCameraUri(context)
                        pendingVisualSearchUri = uri
                        visualCameraLauncher.launch(uri)
                    },
                    onSearchByPhotoGallery = { visualGalleryLauncher.launch("image/*") }
                )
                3 -> BackupExportScreen(vm)
            }

            if (vm.isProcessing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(OrcaObsidian.copy(alpha = 0.65f)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = OrcaIndigo, strokeWidth = 3.dp)
                            Spacer(Modifier.height(14.dp))
                            Text("Traitement en cours...", fontWeight = FontWeight.Bold, color = OrcaObsidian)
                        }
                    }
                }
            }
        }
    }

    // Modal détaillé d'un article : Photo, Code-barres, Prix, Stock +/-, Modifier, + Étiquette, Supprimer
    vm.previewArticleForModal?.let { art ->
        Dialog(onDismissRequest = { vm.previewArticleForModal = null }) {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = art.designation,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp,
                        color = OrcaObsidian
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFEEF2FF),
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "CB : ${art.barcode}  •  ${OcrAndLabelParser.formatOrcaLabelPrice(art.price)}",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = OrcaIndigo
                            )
                            if (art.oldPrice > 0L && art.oldPrice != art.price) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = OcrAndLabelParser.formatOrcaLabelPrice(art.oldPrice),
                                    fontSize = 11.sp,
                                    color = Color.Gray,
                                    textDecoration = TextDecoration.LineThrough
                                )
                            }
                        }
                    }

                    Text(
                        text = "Réf: ${art.refProduit.ifEmpty { "-" }} • ${art.rayon} / ${art.categorie} / ${art.marque}",
                        fontSize = 11.sp,
                        color = Color(0xFF64748B)
                    )

                    Spacer(Modifier.height(10.dp))

                    if (art.photoPath.isNotEmpty() && File(art.photoPath).exists()) {
                        val bmp = remember(art.photoPath) { BitmapFactory.decodeFile(art.photoPath) }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = art.designation,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .border(1.dp, OrcaCardBorder, RoundedCornerShape(14.dp)),
                                contentScale = ContentScale.Fit
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(130.dp)
                                .background(OrcaSurfaceBg, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Aucune photo enregistrée pour cet article", color = Color.Gray, fontSize = 12.sp)
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Ajustement rapide du stock
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = OrcaSurfaceBg,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Stock disponible :", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { vm.adjustArticleStock(art, -1) }) {
                                    Icon(Icons.Rounded.RemoveCircleOutline, "Diminuer stock", tint = OrcaObsidian)
                                }
                                Text("${art.stock}", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = OrcaIndigo)
                                IconButton(onClick = { vm.adjustArticleStock(art, 1) }) {
                                    Icon(Icons.Rounded.AddCircleOutline, "Augmenter stock", tint = OrcaEmerald)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Actions complètes : Modifier / + Étiquette / Supprimer
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { vm.startEditingArticle(art) },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = OrcaIndigo),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Edit, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Modifier", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                vm.enqueueForPrint(art, 1)
                                vm.previewArticleForModal = null
                                currentTab = 2
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = OrcaEmerald),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Print, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("+ Étiquette", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                vm.previewArticleForModal = null
                                vm.articlePendingDelete = art
                            },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, OrcaCrimson),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.DeleteOutline, null, tint = OrcaCrimson, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Supprimer", fontSize = 12.sp, color = OrcaCrimson, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = { vm.previewArticleForModal = null },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Fermer", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // Confirmation suppression unitaire
    vm.articlePendingDelete?.let { art ->
        AlertDialog(
            onDismissRequest = { vm.articlePendingDelete = null },
            shape = RoundedCornerShape(20.dp),
            title = { Text("Supprimer cet article ?", fontWeight = FontWeight.ExtraBold) },
            text = {
                Text("Voulez-vous vraiment supprimer l'article CB : ${art.barcode} (${art.designation}) de la base de données ?")
            },
            confirmButton = {
                Button(
                    onClick = { vm.confirmDeleteArticle(art) },
                    colors = ButtonDefaults.buttonColors(containerColor = OrcaCrimson),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Supprimer", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { vm.articlePendingDelete = null }, shape = RoundedCornerShape(10.dp)) {
                    Text("Annuler")
                }
            }
        )
    }

    // Confirmation suppression par lot
    if (vm.showBatchDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { vm.showBatchDeleteConfirm = false },
            shape = RoundedCornerShape(20.dp),
            title = { Text("Supprimer la sélection ?", fontWeight = FontWeight.ExtraBold) },
            text = {
                Text("Voulez-vous supprimer définitivement ${vm.selectedDbBarcodes.size} article(s) sélectionné(s) ?")
            },
            confirmButton = {
                Button(
                    onClick = { vm.confirmDeleteSelectedArticles() },
                    colors = ButtonDefaults.buttonColors(containerColor = OrcaCrimson),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Supprimer (${vm.selectedDbBarcodes.size})", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { vm.showBatchDeleteConfirm = false }, shape = RoundedCornerShape(10.dp)) {
                    Text("Annuler")
                }
            }
        )
    }

    vm.pendingPriceDiff?.let { (art, newPrice) ->
        AlertDialog(
            onDismissRequest = { vm.pendingPriceDiff = null },
            shape = RoundedCornerShape(20.dp),
            title = { Text("Différence de prix détectée", fontWeight = FontWeight.ExtraBold) },
            text = {
                Text("L'article CB : ${art.barcode} (${art.designation}) est enregistré à ${OcrAndLabelParser.formatOrcaLabelPrice(art.price)}, mais l'étiquette indique ${OcrAndLabelParser.formatOrcaLabelPrice(newPrice)}.")
            },
            confirmButton = {
                Button(onClick = { vm.resolvePriceDiff(true) }, shape = RoundedCornerShape(10.dp)) {
                    Text("Mettre à jour et imprimer")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { vm.resolvePriceDiff(false) }, shape = RoundedCornerShape(10.dp)) {
                    Text("Garder ancien prix")
                }
            }
        )
    }

    if (vm.showVisualSearchModal) {
        Dialog(onDismissRequest = { vm.showVisualSearchModal = false }) {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth().heightIn(max = 540.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("Articles retrouvés par photo", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                    Spacer(Modifier.height(10.dp))
                    if (vm.visualSearchMatches.isEmpty()) {
                        Text("Aucune correspondance trouvée.", fontSize = 13.sp, color = Color.Gray)
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f, fill = false)) {
                            items(vm.visualSearchMatches) { match ->
                                Card(
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(containerColor = OrcaSurfaceBg)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("CB : ${match.article.barcode}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = OrcaIndigo)
                                            Spacer(Modifier.width(8.dp))
                                            Surface(shape = CircleShape, color = Color(0xFFD1FAE5)) {
                                                Text(
                                                    "${match.similarityPercent}% similaire",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = OrcaEmerald,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(match.article.designation, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        Text("Prix : ${OcrAndLabelParser.formatOrcaLabelPrice(match.article.price)}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                                            OutlinedButton(
                                                shape = RoundedCornerShape(10.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                                onClick = { vm.startEditingArticle(match.article) }
                                            ) { Text("Modifier", fontSize = 11.sp) }

                                            OutlinedButton(
                                                shape = RoundedCornerShape(10.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                                onClick = {
                                                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    cb.setPrimaryClip(ClipData.newPlainText("barcode", match.article.barcode))
                                                    vm.statusMessage = "Code-barres ${match.article.barcode} copié !"
                                                }
                                            ) { Text("Copier CB", fontSize = 11.sp) }

                                            Button(
                                                shape = RoundedCornerShape(10.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                                onClick = {
                                                    vm.enqueueForPrint(match.article, 1)
                                                    vm.showVisualSearchModal = false
                                                    currentTab = 2
                                                }
                                            ) { Text("+ Étiquette", fontSize = 11.sp) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { vm.showVisualSearchModal = false },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Fermer") }
                }
            }
        }
    }
}

@Composable
fun CreateOrEditArticleScreen(vm: MagasinViewModel) {
    val context = LocalContext.current
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }

    val takePhotoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok && tempCameraUri != null) vm.attachPhotoToForm(tempCameraUri!!)
    }
    val importPhotoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.attachPhotoToForm(uri)
    }
    val scanLabelCameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok && tempCameraUri != null) vm.scanLabelToFillForm(tempCameraUri!!)
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (vm.isEditing) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFFFEF3C7),
                border = BorderStroke(1.dp, OrcaGold),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Edit, null, tint = OrcaGold)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Mode modification actif", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = OrcaObsidian)
                            Text("Article d'origine : CB ${vm.editingOriginalBarcode}", fontSize = 11.sp, color = OrcaSlate)
                        }
                    }
                    TextButton(onClick = { vm.cancelEditingArticle() }) {
                        Text("Annuler", color = OrcaCrimson, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, OrcaCardBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (vm.isEditing) "Modifier l'article" else "Créer un article",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = OrcaObsidian
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            val uri = ImageAndVisualSearchHelper.createTempCameraUri(context)
                            tempCameraUri = uri
                            takePhotoLauncher.launch(uri)
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 11.dp)
                    ) {
                        Icon(Icons.Rounded.PhotoCamera, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(if (vm.formPhotoPath.isNotEmpty()) "Changer photo" else "Prendre photo", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = { importPhotoLauncher.launch("image/*") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 11.dp)
                    ) {
                        Icon(Icons.Rounded.FileUpload, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Importer", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }

                Button(
                    onClick = {
                        val uri = ImageAndVisualSearchHelper.createTempCameraUri(context)
                        tempCameraUri = uri
                        scanLabelCameraLauncher.launch(uri)
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = OrcaObsidian),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Icon(Icons.Rounded.DocumentScanner, null, tint = Color(0xFFFBBF24))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (vm.isEditing) "Rescanner l'étiquette pour mettre à jour" else "Scan une étiquette pour créer / modifier",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                if (vm.formPhotoPath.isNotEmpty() && File(vm.formPhotoPath).exists()) {
                    val bmp = remember(vm.formPhotoPath) { BitmapFactory.decodeFile(vm.formPhotoPath) }
                    if (bmp != null) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Aperçu",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(155.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .border(1.dp, OrcaCardBorder, RoundedCornerShape(14.dp)),
                                contentScale = ContentScale.Fit
                            )
                            FilledIconButton(
                                onClick = { vm.removePhotoFromForm() },
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = OrcaCrimson),
                                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(30.dp)
                            ) {
                                Icon(Icons.Rounded.Close, "Retirer la photo", tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, OrcaCardBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = vm.formDesignation,
                    onValueChange = { vm.formDesignation = it.uppercase(Locale.ROOT) },
                    label = { Text("Désignation (MAJUSCULES) *") },
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = vm.formBarcode,
                        onValueChange = { vm.formBarcode = it.trim() },
                        label = { Text("Code-barres *") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1.3f)
                    )
                    OutlinedTextField(
                        value = vm.formRefProduit,
                        onValueChange = { vm.formRefProduit = it.uppercase(Locale.ROOT) },
                        label = { Text("Réf. produit") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = vm.formPrice,
                        onValueChange = { vm.formPrice = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Prix (FCFA) *") },
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1.2f)
                    )
                    OutlinedTextField(
                        value = vm.formStock,
                        onValueChange = { vm.formStock = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Stock") },
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(0.8f)
                    )
                }

                if (vm.isEditing) {
                    OutlinedTextField(
                        value = vm.formOldPrice,
                        onValueChange = { vm.formOldPrice = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Ancien prix barré (FCFA - optionnel)") },
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = vm.formRayon,
                    onValueChange = { vm.formRayon = it.uppercase(Locale.ROOT) },
                    label = { Text("Rayon *") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = vm.formCategorie,
                        onValueChange = { vm.formCategorie = it.uppercase(Locale.ROOT) },
                        label = { Text("Catégorie *") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = vm.formMarque,
                        onValueChange = { vm.formMarque = it.uppercase(Locale.ROOT) },
                        label = { Text("Marque *") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    if (vm.isEditing) {
                        OutlinedButton(
                            onClick = { vm.cancelEditingArticle() },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) { Text("Annuler", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                    } else {
                        OutlinedButton(
                            onClick = { vm.fillDemoExample() },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) { Text("Exemple ORCA", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                    }

                    Button(
                        onClick = { vm.saveCurrentArticle() },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (vm.isEditing) OrcaIndigo else OrcaEmerald),
                        modifier = Modifier.weight(1.5f)
                    ) {
                        Icon(if (vm.isEditing) Icons.Rounded.Save else Icons.Rounded.CheckCircle, null)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (vm.isEditing) "Mettre à jour" else "Enregistrer en base",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DatabaseScreen(vm: MagasinViewModel, allArticles: List<ArticleEntity>, onNavigateToLabels: () -> Unit) {
    val context = LocalContext.current
    var tempDbCameraUri by remember { mutableStateOf<Uri?>(null) }

    val scanDbCameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok && tempDbCameraUri != null) vm.scanLabelInDatabase(tempDbCameraUri!!)
    }

    val distinctValues = remember(vm.selectedFilterType, allArticles) {
        when (vm.selectedFilterType) {
            "RAYON" -> allArticles.map { it.rayon }.filter { it.isNotEmpty() }.distinct().sorted()
            "CATEGORIE" -> allArticles.map { it.categorie }.filter { it.isNotEmpty() }.distinct().sorted()
            "MARQUE" -> allArticles.map { it.marque }.filter { it.isNotEmpty() }.distinct().sorted()
            else -> emptyList()
        }
    }

    val filteredArticles = remember(vm.selectedFilterType, vm.selectedFilterValue, vm.dbSearchQuery, allArticles) {
        val q = vm.dbSearchQuery.trim().uppercase(Locale.ROOT)
        allArticles.filter { art ->
            val matchesCategory = when (vm.selectedFilterType) {
                "TOUS", "" -> true
                "RAYON" -> vm.selectedFilterValue.isEmpty() || art.rayon == vm.selectedFilterValue
                "CATEGORIE" -> vm.selectedFilterValue.isEmpty() || art.categorie == vm.selectedFilterValue
                "MARQUE" -> vm.selectedFilterValue.isEmpty() || art.marque == vm.selectedFilterValue
                else -> true
            }
            val matchesQuery = q.isEmpty() ||
                art.barcode.uppercase(Locale.ROOT).contains(q) ||
                art.designation.uppercase(Locale.ROOT).contains(q) ||
                art.refProduit.uppercase(Locale.ROOT).contains(q) ||
                art.rayon.uppercase(Locale.ROOT).contains(q) ||
                art.categorie.uppercase(Locale.ROOT).contains(q) ||
                art.marque.uppercase(Locale.ROOT).contains(q)
            matchesCategory && matchesQuery
        }
    }

    val pageSize = 10
    val totalPages = ((filteredArticles.size + pageSize - 1) / pageSize).coerceAtLeast(1)
    LaunchedEffect(totalPages) { if (vm.currentDbPage > totalPages) vm.currentDbPage = totalPages }
    val pageItems = filteredArticles.drop((vm.currentDbPage.coerceIn(1, totalPages) - 1) * pageSize).take(pageSize)

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Base de données", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = OrcaObsidian)
                Text("${filteredArticles.size} article(s) affiché(s) sur ${allArticles.size}", fontSize = 12.sp, color = Color(0xFF64748B))
            }

            if (filteredArticles.isNotEmpty()) {
                val allFilteredSelected = filteredArticles.all { vm.selectedDbBarcodes.contains(it.barcode) }
                TextButton(
                    onClick = {
                        if (allFilteredSelected) {
                            filteredArticles.forEach { vm.selectedDbBarcodes.remove(it.barcode) }
                        } else {
                            filteredArticles.forEach {
                                if (!vm.selectedDbBarcodes.contains(it.barcode)) vm.selectedDbBarcodes.add(it.barcode)
                            }
                        }
                    }
                ) {
                    Text(
                        if (allFilteredSelected) "Tout désélectionner" else "Tout sélectionner",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Barre de recherche instantanée + bouton Scan étiquette
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = vm.dbSearchQuery,
                onValueChange = {
                    vm.dbSearchQuery = it
                    vm.currentDbPage = 1
                },
                placeholder = { Text("Rechercher CB, nom, réf...", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Rounded.Search, null, modifier = Modifier.size(20.dp)) },
                trailingIcon = {
                    if (vm.dbSearchQuery.isNotEmpty()) {
                        IconButton(onClick = { vm.dbSearchQuery = "" }) {
                            Icon(Icons.Rounded.Close, "Effacer", modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            )

            FilledIconButton(
                onClick = {
                    val uri = ImageAndVisualSearchHelper.createTempCameraUri(context)
                    tempDbCameraUri = uri
                    scanDbCameraLauncher.launch(uri)
                },
                shape = RoundedCornerShape(12.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = OrcaObsidian),
                modifier = Modifier.size(52.dp)
            ) {
                Icon(Icons.Rounded.DocumentScanner, "Scanner une étiquette", tint = Color(0xFFFBBF24))
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        ) {
            listOf("TOUS" to "Tous", "RAYON" to "Rayon", "CATEGORIE" to "Catégorie", "MARQUE" to "Marque").forEach { (key, label) ->
                FilterChip(
                    selected = vm.selectedFilterType == key,
                    onClick = {
                        vm.selectedFilterType = key
                        vm.selectedFilterValue = ""
                        vm.currentDbPage = 1
                    },
                    shape = RoundedCornerShape(10.dp),
                    label = { Text(label, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                )
            }
        }

        if (vm.selectedFilterType != "TOUS" && vm.selectedFilterType.isNotEmpty() && distinctValues.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            ) {
                distinctValues.forEach { v ->
                    FilterChip(
                        selected = vm.selectedFilterValue == v,
                        onClick = {
                            vm.selectedFilterValue = if (vm.selectedFilterValue == v) "" else v
                            vm.currentDbPage = 1
                        },
                        shape = CircleShape,
                        label = { Text(v, fontSize = 12.sp, fontWeight = if (vm.selectedFilterValue == v) FontWeight.Bold else FontWeight.Normal) }
                    )
                }
            }
        }

        // Barre d'actions groupées quand des articles sont cochés
        if (vm.selectedDbBarcodes.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        vm.selectedDbBarcodes.forEach { bc ->
                            allArticles.find { it.barcode == bc }?.let { vm.enqueueForPrint(it, 1) }
                        }
                        vm.selectedDbBarcodes.clear()
                        onNavigateToLabels()
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1.4f)
                ) {
                    Icon(Icons.Rounded.Print, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("+ Étiquettes (${vm.selectedDbBarcodes.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = { vm.showBatchDeleteConfirm = true },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, OrcaCrimson),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.DeleteOutline, null, tint = OrcaCrimson, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Supprimer (${vm.selectedDbBarcodes.size})", fontSize = 12.sp, color = OrcaCrimson, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        if (filteredArticles.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Aucun article ne correspond à votre recherche.", color = Color(0xFF64748B), fontSize = 13.sp)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(pageItems, key = { it.barcode }) { art ->
                    val isChecked = vm.selectedDbBarcodes.contains(art.barcode)
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, if (isChecked) OrcaIndigo else OrcaCardBorder),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.previewArticleForModal = art }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        if (checked) vm.selectedDbBarcodes.add(art.barcode)
                                        else vm.selectedDbBarcodes.remove(art.barcode)
                                    }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = Color(0xFFEEF2FF)
                                        ) {
                                            Text(
                                                text = "CB : ${art.barcode}",
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = OrcaIndigo,
                                                fontSize = 12.sp,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                        Text(
                                            text = OcrAndLabelParser.formatOrcaLabelPrice(art.price),
                                            fontSize = 15.sp,
                                            color = OrcaEmerald,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(art.designation, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = OrcaObsidian)
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Réf: ${art.refProduit.ifEmpty { "-" }} • ${art.rayon} / ${art.categorie} / ${art.marque} • Stock: ${art.stock}",
                                        fontSize = 11.sp,
                                        color = Color(0xFF64748B)
                                    )
                                }
                            }

                            HorizontalDivider(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp), color = OrcaSurfaceBg)

                            // Barre d'actions directes sur chaque article : Modifier / + Étiquette / Supprimer
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = { vm.startEditingArticle(art) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                                ) {
                                    Icon(Icons.Rounded.Edit, null, modifier = Modifier.size(16.dp), tint = OrcaIndigo)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Modifier", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = OrcaIndigo)
                                }

                                TextButton(
                                    onClick = {
                                        vm.enqueueForPrint(art, 1)
                                        vm.statusMessage = "CB : ${art.barcode} ajouté à l'impression."
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                                ) {
                                    Icon(Icons.Rounded.Print, null, modifier = Modifier.size(16.dp), tint = OrcaEmerald)
                                    Spacer(Modifier.width(4.dp))
                                    Text("+ Étiquette", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = OrcaEmerald)
                                }

                                IconButton(
                                    onClick = { vm.articlePendingDelete = art },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.Rounded.DeleteOutline, "Supprimer", tint = OrcaCrimson, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    enabled = vm.currentDbPage > 1,
                    shape = RoundedCornerShape(10.dp),
                    onClick = { vm.currentDbPage-- }
                ) { Text("Précédent") }

                Text("Page ${vm.currentDbPage} / $totalPages", fontSize = 12.sp, fontWeight = FontWeight.Bold)

                OutlinedButton(
                    enabled = vm.currentDbPage < totalPages,
                    shape = RoundedCornerShape(10.dp),
                    onClick = { vm.currentDbPage++ }
                ) { Text("Suivant") }
            }
        }
    }
}

@Composable
fun LabelsPrintScreen(
    vm: MagasinViewModel,
    onSearchByPhotoCamera: () -> Unit,
    onSearchByPhotoGallery: () -> Unit
) {
    val context = LocalContext.current
    var tempLabelCameraUri by remember { mutableStateOf<Uri?>(null) }

    val photoEtiquetteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok && tempLabelCameraUri != null) vm.scanLabelInPrintQueue(tempLabelCameraUri!!)
    }

    val exportWordLabelsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    ) { uri ->
        if (uri != null) vm.exportWordLabels(uri)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Étiquettes (ORCA DECO)", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = OrcaObsidian)
            if (vm.labelPrintQueue.isNotEmpty()) {
                TextButton(onClick = { vm.clearPrintQueue() }) {
                    Icon(Icons.Rounded.DeleteSweep, null, tint = OrcaCrimson, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Vider", color = OrcaCrimson, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    val uri = ImageAndVisualSearchHelper.createTempCameraUri(context)
                    tempLabelCameraUri = uri
                    photoEtiquetteLauncher.launch(uri)
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1.3f)
            ) {
                Icon(Icons.Rounded.PhotoCamera, null)
                Spacer(Modifier.width(6.dp))
                Text("Photo étiquette", fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = onSearchByPhotoCamera,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Rounded.ImageSearch, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Photo produit", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Départ A4 :", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = vm.printStartOffset.toString(),
                onValueChange = { vm.printStartOffset = it.toIntOrNull()?.coerceIn(1, 14) ?: 1 },
                shape = RoundedCornerShape(10.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(62.dp)
            )
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                enabled = vm.labelPrintQueue.isNotEmpty(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.5.dp, OrcaIndigo),
                onClick = {
                    exportWordLabelsLauncher.launch("Etiquettes_RVOITURE_ORCA.docx")
                }
            ) {
                Icon(Icons.Rounded.Description, null, tint = OrcaIndigo, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Word .docx", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = OrcaIndigo)
            }
            Button(
                enabled = vm.labelPrintQueue.isNotEmpty(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OrcaEmerald),
                onClick = {
                    val expandedArticles = vm.labelPrintQueue.flatMap { item ->
                        List(item.quantity) { item.article }
                    }
                    BarcodeAndPrintHelper.printLabelsSheet(context, expandedArticles, vm.printStartOffset)
                }
            ) {
                Icon(Icons.Rounded.Print, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Imprimer (${vm.labelPrintQueue.sumOf { it.quantity }})", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(12.dp))

        if (vm.labelPrintQueue.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Aucune étiquette en attente.\nScannez une étiquette ou sélectionnez un article dans la Base.",
                    color = Color(0xFF64748B),
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                items(vm.labelPrintQueue, key = { it.article.barcode }) { item ->
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.5.dp, Color(0xFF2F5597)),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = item.article.designation,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF475569),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { vm.startEditingArticle(item.article) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Rounded.Edit, "Modifier cet article", tint = OrcaIndigo, modifier = Modifier.size(16.dp))
                                }
                            }

                            Spacer(Modifier.height(4.dp))

                            Text(
                                text = OcrAndLabelParser.formatOrcaLabelPrice(item.article.price),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 28.sp,
                                color = Color.Black,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )

                            Spacer(Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Image(
                                        painter = painterResource(id = R.drawable.logo_orca_deco),
                                        contentDescription = "Logo ORCA DECO",
                                        modifier = Modifier.height(34.dp).width(78.dp),
                                        contentScale = ContentScale.Fit
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = "CB : ${item.article.barcode}",
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 13.sp,
                                        color = Color.Black
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { vm.updatePrintQty(item.article.barcode, -1) }) {
                                        Icon(Icons.Rounded.RemoveCircleOutline, null, tint = OrcaObsidian)
                                    }
                                    Text("${item.quantity}", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                                    IconButton(onClick = { vm.updatePrintQty(item.article.barcode, 1) }) {
                                        Icon(Icons.Rounded.AddCircleOutline, null, tint = OrcaIndigo)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BackupExportScreen(vm: MagasinViewModel) {
    val context = LocalContext.current

    val exportExcelLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri -> if (uri != null) vm.exportExcel(uri) }

    val exportWordLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    ) { uri -> if (uri != null) vm.exportWord(uri) }

    val exportPdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> if (uri != null) vm.exportPdf(uri) }

    val importDocLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            var name = "import.xlsx"
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && idx >= 0) name = c.getString(idx)
            }
            vm.importStructuredDocument(uri, name)
        }
    }

    val backupJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) vm.exportFullBackup(uri) }

    val restoreJsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.restoreFullBackup(uri)
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Import / Export & Sauvegarde Locale", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = OrcaObsidian)

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, OrcaCardBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Exporter la base de données", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { exportExcelLauncher.launch("Base_Articles.xlsx") }, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                        Text("Excel")
                    }
                    Button(onClick = { exportWordLauncher.launch("Base_Articles.docx") }, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                        Text("Word")
                    }
                    Button(onClick = { exportPdfLauncher.launch("Base_Articles.pdf") }, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                        Text("PDF")
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Text("Importer depuis Excel ou Word", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                OutlinedButton(
                    onClick = {
                        importDocLauncher.launch(arrayOf(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/octet-stream"
                        ))
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.UploadFile, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Importer un fichier Excel (.xlsx) ou Word (.docx)")
                }
            }
        }

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, OrcaCardBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Sauvegarde et Restauration complète en local", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Button(
                    onClick = { backupJsonLauncher.launch("Sauvegarde_Magasin_Complete.json") },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = OrcaObsidian),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Save, null, tint = Color(0xFFFBBF24))
                    Spacer(Modifier.width(8.dp))
                    Text("Sauvegarder la base + photos (.json)", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = { restoreJsonLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Restore, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Restaurer une sauvegarde locale (.json)")
                }
            }
        }
    }
}
