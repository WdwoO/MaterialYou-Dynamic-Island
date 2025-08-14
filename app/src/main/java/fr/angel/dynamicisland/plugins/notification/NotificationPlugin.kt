package fr.angel.dynamicisland.plugins.notification

import android.annotation.SuppressLint
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.INPUT_METHOD_SERVICE
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DismissDirection
import androidx.compose.material.DismissValue
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.FractionalThreshold
import androidx.compose.material.SwipeToDismiss
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.rememberDismissState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skydoves.landscapist.rememberDrawablePainter
import fr.angel.dynamicisland.island.IslandSettings
import fr.angel.dynamicisland.model.ACTION_CLOSE
import fr.angel.dynamicisland.model.ACTION_OPEN_CLOSE
import fr.angel.dynamicisland.model.NOTIFICATION_POSTED
import fr.angel.dynamicisland.model.NOTIFICATION_REMOVED
import fr.angel.dynamicisland.model.service.IslandOverlayService
import fr.angel.dynamicisland.model.service.NotificationService
import fr.angel.dynamicisland.plugins.BasePlugin
import fr.angel.dynamicisland.plugins.PluginSettingsItem


class NotificationPlugin(
	override val id: String = "NotificationPlugin",
	override val name: String = "Notification",
	override val description: String = "Show the current notification on the screen and allow you to interact with it (reply, open, etc.)",
	override var enabled: MutableState<Boolean> = mutableStateOf(false),
	override val permissions: ArrayList<String> = arrayListOf(
		Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
	),
	override var pluginSettings: MutableMap<String, PluginSettingsItem> = mutableMapOf(),
) : BasePlugin() {

	private lateinit var context: IslandOverlayService
	private val notificationService = NotificationService.getInstance()

	private var notificationMeta : MutableState<NotificationMeta?> = mutableStateOf(null)

	private val handler = Handler(Looper.getMainLooper())

	private val mBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {

			val extras : Bundle = intent.extras ?: return

			if (intent.action == NOTIFICATION_POSTED) {

				// Add notification to list
				val notification = notificationService?.notifications?.lastOrNull { it.id == extras.getInt("id") } ?: return
				// notifications.add(notification)

				// Update notification meta
				var total = ""
				notificationService.notifications.filter { it.id == notification.id }.forEach { total += it.notification.extras.getString("android.text") }

				Log.d("NotificationPlugin", "Notification posted: ${notification.notification.extras.getString("android.title")} - ${notification.notification.extras.getString("android.text")}")

				notificationMeta.value = NotificationMeta(
					title = extras.getString("title"),
					body = notification.notification.extras.getString("android.text") ?: "",
					id = extras.getInt("id"),
					iconDrawable = notification.notification.smallIcon.loadDrawable(context) ?: return,
					packageName = extras.getString("package_name") ?: "fr.angel.dynamicisland",
					actions = (notification.notification.actions ?: arrayOf()).toList(),
					all = extras,
					statusBarNotification = notification
				)

				// Setup timeout
				restartTimeout()

				// Add plugin
				Log.d("NotificationPlugin", "BroadcastReceiver: Add plugin")
				this@NotificationPlugin.context.addPlugin(this@NotificationPlugin)
			}
			if (intent.action == NOTIFICATION_REMOVED) {
				// val id = extras.getInt("id")
				// removeNotificationAndUpdateState(id, true) // Notification already removed
				Log.d("NotificationPlugin", "id: $id")
				if (notificationService?.notifications?.isEmpty() == true) {
					this@NotificationPlugin.context.removePlugin(this@NotificationPlugin)
				}
			}
		}
	}

	private fun removeNotificationAndUpdateState(id: Int) {

		// Remove notification from list
		notificationService?.notifications?.removeAll { it.id == id }

		// Reset timeout
		handler.removeCallbacksAndMessages(null)

		// Update notification meta if list is not empty else set to null
		notificationMeta.value = notificationService?.notifications?.firstOrNull()?.let { notification ->
			NotificationMeta(
				title = notification.notification.extras.getString("android.title"),
				body = notification.notification.extras.getString("android.text") ?: "",
				id = notification.id,
				iconDrawable = notification.notification.smallIcon.loadDrawable(context) ?: return,
				packageName = notification.packageName,
				actions = notification.notification.actions.toList(),
				all = notification.notification.extras,
				statusBarNotification = notification
			)
		}

		// If no more notifications, remove plugin
		if (notificationService?.notifications?.isEmpty() == true) {
			// Remove plugin
			Log.d("NotificationPlugin", "BroadcastReceiver: Remove plugin")
			this@NotificationPlugin.context.removePlugin(this@NotificationPlugin)
		}/* else {
			// Setup timeout
			handler.removeCallbacksAndMessages(null)
			startTimeout()
		}*/
	}

	override fun canExpand(): Boolean { return true }

	@SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(context: IslandOverlayService?) {
		this.context = context ?: return
		val intentFilter = IntentFilter().apply {
			addAction(NOTIFICATION_POSTED)
			addAction(NOTIFICATION_REMOVED)
		}

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
			context.registerReceiver(mBroadcastReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
		else
			context.registerReceiver(mBroadcastReceiver, intentFilter)
	}

	@OptIn(ExperimentalMaterialApi::class, ExperimentalMaterial3Api::class)
	@Composable
	override fun Composable() {
		val meta = notificationMeta.value ?: return

		// Cancel notification timeout
		handler.removeCallbacksAndMessages(null)

		val dismissState = rememberDismissState(
			confirmStateChange = {
				if (it == DismissValue.DismissedToStart || it == DismissValue.DismissedToEnd) {
					context.sendBroadcast(Intent(ACTION_CLOSE))
				}
				true
			}
		)
		SwipeToDismiss(
			state = dismissState,
			dismissThresholds = { FractionalThreshold(0.5f) },
			background = {
				when (dismissState.dismissDirection) {
					DismissDirection.StartToEnd -> {
						Box(
							modifier = Modifier
								.fillMaxSize()
								.background(MaterialTheme.colorScheme.primaryContainer)
						) {
							Icon(
								imageVector = Icons.Default.Delete,
								contentDescription = null,
								modifier = Modifier
									.align(Alignment.CenterStart)
									.padding(start = 16.dp)
							)
						}
					}
					DismissDirection.EndToStart -> {
						Box(
							modifier = Modifier
								.fillMaxSize()
								.background(MaterialTheme.colorScheme.primaryContainer)
						) {
							Icon(
								imageVector = Icons.Default.Delete,
								contentDescription = null,
								modifier = Modifier
									.align(Alignment.CenterEnd)
									.padding(end = 16.dp)
							)
						}
					}
					else -> {
						Box(
							modifier = Modifier
								.fillMaxSize()
								.background(MaterialTheme.colorScheme.background)
						)
					}
				}
			},
			directions = setOf(DismissDirection.StartToEnd, DismissDirection.EndToStart),
		) {
			Column(
				modifier = Modifier
					.fillMaxSize()
					.clip(RoundedCornerShape(context.islandState.cornerPercentage))
					.background(MaterialTheme.colorScheme.background)
					.padding(16.dp),
			) {
				// Notification title + app name + close button + icon
				Row(modifier = Modifier.fillMaxWidth(),
					verticalAlignment = Alignment.CenterVertically
				) {
					// Icon
					Icon(painter = rememberDrawablePainter(drawable = meta.iconDrawable),
						contentDescription = null,
						tint = MaterialTheme.colorScheme.primary,
						modifier = Modifier.size(48.dp)
					)
					Spacer(modifier = Modifier.width(16.dp))
					// Title + app name
					Column(modifier = Modifier.weight(1f)) {
						Text(
							text = meta.getAppName(context),
							style = MaterialTheme.typography.titleMedium,
							overflow = TextOverflow.Ellipsis,
							maxLines = 1,
						)
						if (meta.title != null) {
							Text(text = meta.title!!,
								style = MaterialTheme.typography.titleSmall,
								overflow = TextOverflow.Ellipsis,
								maxLines = 1
							)
						}
					}
					// Close button
					IconButton(modifier = Modifier.align(Alignment.Top),
						onClick = {
							context.shrink()
							restartTimeout()
						}
					) { Icon(imageVector = Icons.Default.ExpandLess, contentDescription = null) }
				}
				Spacer(modifier = Modifier.height(4.dp))
				// Notification body
				Box(modifier = Modifier.weight(1f),
					contentAlignment = Alignment.CenterStart
				) {
					Text(
						text = meta.body,
						style = MaterialTheme.typography.bodySmall,
						textAlign = TextAlign.Justify,
						overflow = TextOverflow.Ellipsis,
					)
				}
				// Notification actions
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.spacedBy(8.dp)
				) {
					var isReplying by remember { mutableStateOf(false) }
					var replyText by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }

					meta.actions.forEach { action ->
						val remoteInput = action.remoteInputs?.firstOrNull()

						if (!isReplying) {
							// Show buttons
							if (remoteInput == null) {
								// Not a reply button
								Button(
									modifier = Modifier.weight(1f),
									onClick = {
										// Classic action
										val intent = action.actionIntent
										intent.send()
										// Remove notification
										context.sendBroadcast(Intent(ACTION_CLOSE).apply {
											putExtra("id", meta.id)
										})
									},
								) {
									Text(
										text = action.title.toString(),
										style = MaterialTheme.typography.labelSmall,
									)
								}
							} else {
								// Reply button
								Button(
									modifier = Modifier.weight(1f),
									onClick = {
										// Reply action
										isReplying = true
									},
								) {
									Text(
										text = action.title.toString(),
										style = MaterialTheme.typography.labelSmall,
									)
								}
							}
						} else if (remoteInput != null) {
							// Create reply text field
							Row(
								modifier = Modifier
									.weight(1f),
								verticalAlignment = Alignment.CenterVertically
							) {


								TextField(
									value = replyText,
									onValueChange = { replyText = it },
									modifier = Modifier
										.weight(1f)
										.onFocusChanged {
											Log.d("Focus", it.isFocused.toString())
											val imm =
												context.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
											if (it.isFocused) {
												imm.toggleSoftInput(
													InputMethodManager.SHOW_FORCED,
													0
												)
											}
										}
										.displayCutoutPadding(),
									shape = CircleShape,
									singleLine = true,
									colors = TextFieldDefaults.textFieldColors(
										focusedIndicatorColor = Color.Transparent,
										unfocusedIndicatorColor = Color.Transparent,
										disabledIndicatorColor = Color.Transparent,
										errorIndicatorColor = Color.Transparent,
									),
								)
								IconButton(onClick = {
									// Send reply action
									val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
									val bundle = Bundle().apply {
										putCharSequence(remoteInput.resultKey, replyText.text)
									}
									RemoteInput.addResultsToIntent(action.remoteInputs, intent, bundle)
									action.actionIntent.send(context, 0, intent)
									// Remove notification
									context.sendBroadcast(Intent(ACTION_CLOSE).apply {
										putExtra("id", meta.id)
									})
								}) {
									Icon(
										imageVector = Icons.Default.Send,
										contentDescription = null
									)
								}
							}
						}
					}
				}
			}
		}
	}

	override fun onClick() {
		val meta = notificationMeta.value ?: return
		val intent = Intent(ACTION_OPEN_CLOSE)
		intent.putExtra("id", meta.id)
		context.sendBroadcast(intent)
	}

	override fun onDestroy() {
		if (!::context.isInitialized) return
		try {
			context.unregisterReceiver(mBroadcastReceiver)
		} catch (_: Exception) {} // Ignore exception if receiver is not registered
	}

	@Composable
	override fun PermissionsRequired() {

	}

	@Composable
	override fun LeftOpenedComposable() {
		val meta = notificationMeta.value ?: return

		Box(
			modifier = Modifier
				.clip(CircleShape)
				.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
		) {
			Icon(
				painter = rememberDrawablePainter(drawable = meta.iconDrawable),
				tint = MaterialTheme.colorScheme.primary,
				contentDescription = null,
				modifier = Modifier.padding(2.dp)
			)
		}
	}

	@Composable
	override fun RightOpenedComposable() {
		when (notificationMeta.value?.statusBarNotification?.notification?.category) {
			"msg" -> {
				Box(
					modifier = Modifier
						.clip(CircleShape)
						.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
				) {
					Icon(
						imageVector = Icons.Default.Chat,
						tint = MaterialTheme.colorScheme.primary,
						contentDescription = null,
						modifier = Modifier.padding(4.dp)
					)
				}
			}
		}
	}

	override fun onLeftSwipe() {
		Log.d("Notification", "Left swipe")
		context.sendBroadcast(Intent(ACTION_CLOSE))
		context.removePlugin(this)
	}

	override fun onRightSwipe() {}

	private fun restartTimeout() {
		handler.removeCallbacksAndMessages(null)
		handler.postDelayed({
			if (notificationMeta.value != null) {
				removeNotificationAndUpdateState(notificationMeta.value!!.id)
				Log.d("NotificationPlugin", "Timeout: Remove notification")
			}
		}, IslandSettings.instance.autoHideOpenedAfter.toLong())
	}
}