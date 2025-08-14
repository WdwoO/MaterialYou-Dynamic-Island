package fr.angel.dynamicisland.model.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_SCREEN_OFF
import android.content.Intent.ACTION_SCREEN_ON
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
import android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
import android.view.WindowManager.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.compositionContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import fr.angel.dynamicisland.R
import fr.angel.dynamicisland.island.Island
import fr.angel.dynamicisland.island.IslandState
import fr.angel.dynamicisland.island.IslandViewState
import fr.angel.dynamicisland.model.MyLifecycleOwner
import fr.angel.dynamicisland.model.SETTINGS_CHANGED
import fr.angel.dynamicisland.model.SETTINGS_KEY
import fr.angel.dynamicisland.model.SETTINGS_THEME_INVERTED
import fr.angel.dynamicisland.model.THEME_INVERTED
import fr.angel.dynamicisland.plugins.BasePlugin
import fr.angel.dynamicisland.plugins.ExportedPlugins
import fr.angel.dynamicisland.ui.island.IslandApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch


class IslandOverlayService : AccessibilityService() {

	private val params = WindowManager.LayoutParams(
		WRAP_CONTENT,
		WRAP_CONTENT,
		TYPE_ACCESSIBILITY_OVERLAY,
		FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS or FLAG_NOT_TOUCH_MODAL or FLAG_NOT_FOCUSABLE,
		PixelFormat.TRANSLUCENT
	).apply {
		gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
	}

	private lateinit var settingsPreferences: SharedPreferences

	// State of the overlay
	var islandState : IslandState by mutableStateOf(IslandViewState.Closed)
		private set

	// Plugins
	private val plugins: ArrayList<BasePlugin> = ExportedPlugins.plugins
	val bindedPlugins = mutableStateListOf<BasePlugin>()

	// Theme
	var invertedTheme by mutableStateOf(false)

	companion object {
		private var instance: IslandOverlayService? = null

		fun getInstance(): IslandOverlayService? {
			return instance
		}
	}

	private val mBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			when (intent.action) {
				SETTINGS_CHANGED -> {
					init()
				}
				SETTINGS_THEME_INVERTED -> {
					val settingsPreferences = getSharedPreferences(SETTINGS_KEY, MODE_PRIVATE)
					invertedTheme = settingsPreferences.getBoolean(THEME_INVERTED, false)
				}
				ACTION_SCREEN_ON -> {
					Island.isScreenOn = true
				}
				ACTION_SCREEN_OFF -> {
					Island.isScreenOn = false
				}
			}
		}
	}

	@SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onServiceConnected() {
		super.onServiceConnected()
		setTheme(R.style.Theme_DynamicIsland)
		instance = this
		settingsPreferences = getSharedPreferences(SETTINGS_KEY, MODE_PRIVATE)

		// Register broadcast receiver
		val intentFilter = IntentFilter().apply {
			addAction(SETTINGS_CHANGED)
			addAction(SETTINGS_THEME_INVERTED)
			addAction(ACTION_SCREEN_ON)
			addAction(ACTION_SCREEN_OFF)
		}

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mBroadcastReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
        }else{
			registerReceiver(mBroadcastReceiver, intentFilter)
		}

        // Setup plugins (check if they are enabled)
		ExportedPlugins.setupPlugins(context = this)

		// Setup
		init()

		val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
		showOverlay(windowManager, params)
	}

	fun init() {
		// Remove plugins
		plugins.forEach {
			it.onDestroy()
		}

		// Remove binded plugins
		bindedPlugins.forEach {
			bindedPlugins.remove(it)
		}

		// Reset island state
		islandState = IslandViewState.Closed

		// Initialize the plugins
		plugins.forEach {
			if (!it.active) return@forEach
			it.onCreate(this)
			Log.d("OverlayService", "Plugin ${it.name} initialized")
		}

		// Setup inverted theme
		val settingsPreferences = getSharedPreferences(SETTINGS_KEY, MODE_PRIVATE)
		invertedTheme = settingsPreferences.getBoolean(THEME_INVERTED, false)
	}

	@SuppressLint("ClickableViewAccessibility")
	private fun showOverlay(
		windowManager: WindowManager,
		params: WindowManager.LayoutParams
	) {
		val composeView = ComposeView(this)
		// Add effects when notification received, swiped, etc.
		val composeEffectView = ComposeView(this)

		composeView.setContent {
			// Listen for plugin changes
			LaunchedEffect(bindedPlugins.firstOrNull()) {
				islandState = if (bindedPlugins.firstOrNull() != null) {
					IslandViewState.Opened
				} else {
					IslandViewState.Closed
				}
				Log.d("OverlayService", "Plugins changed: $bindedPlugins")
			}

			IslandApp(
				islandOverlayService = this,
			)
		}

		// TODO: Find a way to detect when a click is performed outside of the overlay (to close it)
		/*composeView.setOnTouchListener { view: View?, event: MotionEvent ->
			if (event.action == MotionEvent.ACTION_DOWN) {
				Log.d("OverlayService", "Touch event")
			}
			false
		}*/

		// Trick The ComposeView into thinking we are tracking lifecycle
		/*val viewModelStore = ViewModelStore()
		val lifecycleOwner = MyLifecycleOwner()
		lifecycleOwner.performRestore(null)
		lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
		ViewTreeLifecycleOwner.set(composeView, lifecycleOwner)
		ViewTreeViewModelStoreOwner.set(composeView) { viewModelStore }
		composeView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)*/

		val viewModelStore = ViewModelStore()
		val viewModelStoreOwner = object : ViewModelStoreOwner {
			override val viewModelStore: ViewModelStore
				get() = viewModelStore
		}

		val lifecycleOwner = MyLifecycleOwner()
		lifecycleOwner.performRestore(null)
		lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
		composeView.setViewTreeLifecycleOwner(lifecycleOwner)
		composeView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
		composeView.setViewTreeViewModelStoreOwner(viewModelStoreOwner)

		// Make recomposition happen on the UI thread
		val coroutineContext = AndroidUiDispatcher.CurrentThread
		val runRecomposeScope = CoroutineScope(coroutineContext)
		val recomposer = Recomposer(coroutineContext)
		composeView.compositionContext = recomposer
		runRecomposeScope.launch {
			recomposer.runRecomposeAndApplyChanges()
		}

		// Add the view to the window
		windowManager.addView(composeView, params)
	}

	fun addPlugin(plugin: BasePlugin) {
		// Check for existing plugin with same id
		if (bindedPlugins.any { it.id == plugin.id }) {
			Log.d("OverlayService", "Plugin with id ${plugin.id} already binded")
			return
		}
		if (bindedPlugins.isNotEmpty() && plugins.indexOf(plugin) < plugins.indexOf(bindedPlugins.first())) {
			bindedPlugins.add(0, plugin)
			Log.d("OverlayService", "Plugin with id ${plugin.id} added at the beginning")
		} else {
			bindedPlugins.add(plugin)
			Log.d("OverlayService", "Plugin with id ${plugin.id} added at the end")
		}
	}
	fun removePlugin(plugin: BasePlugin) {
		Log.d("OverlayService", "Plugin with id ${plugin.id} removed")
		bindedPlugins.removeIf { it.id == plugin.id }
	}

	fun expand() { islandState = IslandViewState.Expanded(configuration = resources.configuration) }
	fun shrink() { islandState = IslandViewState.Opened }

	override fun onUnbind(intent: Intent?): Boolean {
		instance = null
		return super.onUnbind(intent)
	}

	override fun onDestroy() {
		super.onDestroy()
		plugins.forEach { it.onDestroy() }
	}

	override fun onConfigurationChanged(newConfig: Configuration) {
		super.onConfigurationChanged(newConfig)
		Island.isInLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
	}

	override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
	override fun onInterrupt() {}
}