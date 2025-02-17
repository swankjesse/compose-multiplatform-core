/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui.scene

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ExperimentalComposeApi
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.HistoricalChange
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.interop.LocalUIKitInteropContainer
import androidx.compose.ui.interop.LocalUIKitInteropContext
import androidx.compose.ui.interop.UIKitInteropContainer
import androidx.compose.ui.interop.UIKitInteropContext
import androidx.compose.ui.node.TrackInteropContainer
import androidx.compose.ui.platform.AccessibilityMediator
import androidx.compose.ui.platform.AccessibilitySyncOptions
import androidx.compose.ui.platform.DefaultInputModeManager
import androidx.compose.ui.platform.EmptyViewConfiguration
import androidx.compose.ui.platform.LocalLayoutMargins
import androidx.compose.ui.platform.LocalSafeArea
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.PlatformInsets
import androidx.compose.ui.platform.PlatformWindowContext
import androidx.compose.ui.platform.UIKitTextInputService
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.uikit.ComposeUIViewControllerConfiguration
import androidx.compose.ui.uikit.LocalKeyboardOverlapHeight
import androidx.compose.ui.uikit.systemDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.asCGRect
import androidx.compose.ui.unit.asDpOffset
import androidx.compose.ui.unit.asDpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.roundToIntRect
import androidx.compose.ui.unit.toDpRect
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.window.ComposeSceneKeyboardOffsetManager
import androidx.compose.ui.window.ApplicationForegroundStateListener
import androidx.compose.ui.window.FocusStack
import androidx.compose.ui.window.InteractionUIView
import androidx.compose.ui.window.KeyboardEventHandler
import androidx.compose.ui.window.KeyboardVisibilityListener
import androidx.compose.ui.window.RenderingUIView
import androidx.compose.ui.window.UITouchesEventPhase
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt
import kotlinx.cinterop.CValue
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import org.jetbrains.skia.Canvas
import org.jetbrains.skiko.SkikoRenderDelegate
import platform.CoreGraphics.CGAffineTransformIdentity
import platform.CoreGraphics.CGAffineTransformInvert
import platform.CoreGraphics.CGAffineTransformMakeTranslation
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGRectZero
import platform.CoreGraphics.CGSize
import platform.QuartzCore.CATransaction
import platform.UIKit.NSLayoutConstraint
import platform.UIKit.UIEvent
import platform.UIKit.UITouch
import platform.UIKit.UITouchPhase
import platform.UIKit.UIView
import platform.UIKit.UIViewControllerTransitionCoordinatorProtocol
import platform.UIKit.UIWindow

/**
 * Layout of sceneView on the screen
 */
internal sealed interface SceneLayout {
    object Undefined : SceneLayout
    object UseConstraintsToFillContainer : SceneLayout
    class UseConstraintsToCenter(val size: CValue<CGSize>) : SceneLayout
    class Bounds(val renderBounds: IntRect, val interactionBounds: IntRect) : SceneLayout
}

/**
 * iOS specific-implementation of [PlatformContext.SemanticsOwnerListener] used to track changes in [SemanticsOwner].
 *
 * @property rootView The UI container associated with the semantics owner.
 * @property coroutineContext The coroutine context to use for handling semantics changes.
 * @property getAccessibilitySyncOptions A lambda function to retrieve the latest accessibility synchronization options.
 * @property performEscape A lambda to delegate accessibility escape operation. Returns true if the escape was handled, false otherwise.
 */
@OptIn(ExperimentalComposeApi::class)
private class SemanticsOwnerListenerImpl(
    private val rootView: UIView,
    private val coroutineContext: CoroutineContext,
    private val getAccessibilitySyncOptions: () -> AccessibilitySyncOptions,
    private val convertToAppWindowCGRect: (Rect, UIWindow) -> CValue<CGRect>,
    private val performEscape: () -> Boolean
) : PlatformContext.SemanticsOwnerListener {
    var current: Pair<SemanticsOwner, AccessibilityMediator>? = null

    override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) {
        if (current == null) {
            current = semanticsOwner to AccessibilityMediator(
                rootView,
                semanticsOwner,
                coroutineContext,
                getAccessibilitySyncOptions,
                convertToAppWindowCGRect,
                performEscape
            )
        }
    }

    override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) {
        val current = current ?: return

        if (current.first == semanticsOwner) {
            current.second.dispose()
            this.current = null
        }
    }

    override fun onSemanticsChange(semanticsOwner: SemanticsOwner) {
        val current = current ?: return

        if (current.first == semanticsOwner) {
            current.second.onSemanticsChange()
        }
    }

    override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) {
        val current = current ?: return

        if (current.first == semanticsOwner) {
            current.second.onLayoutChange(nodeId = semanticsNodeId)
        }
    }
}

private class RenderingUIViewDelegateImpl(
    private val scene: ComposeScene,
    private val sceneOffset: () -> Offset,
) : SkikoRenderDelegate {
    override fun onRender(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
        canvas.withSceneOffset {
            scene.render(asComposeCanvas(), nanoTime)
        }
    }

    private inline fun Canvas.withSceneOffset(block: Canvas.() -> Unit) {
        val sceneOffset = sceneOffset()
        save()
        translate(sceneOffset.x, sceneOffset.y)
        block()
        restore()
    }
}

private class ComposeSceneMediatorRootUIView : UIView(CGRectZero.readValue()) {
    override fun hitTest(point: CValue<CGPoint>, withEvent: UIEvent?): UIView? {
        // forwards touches forward to the children, is never a target for a touch
        val result = super.hitTest(point, withEvent)

        return if (result == this) {
            null
        } else {
            result
        }
    }
}

internal class ComposeSceneMediator(
    private val container: UIView,
    private val configuration: ComposeUIViewControllerConfiguration,
    private val focusStack: FocusStack<UIView>?,
    private val windowContext: PlatformWindowContext,
    /**
     * @see PlatformContext.measureDrawLayerBounds
     */
    private val measureDrawLayerBounds: Boolean = false,
    val coroutineContext: CoroutineContext,
    private val renderingUIViewFactory: (UIKitInteropContext, SkikoRenderDelegate) -> RenderingUIView,
    composeSceneFactory: (
        invalidate: () -> Unit,
        platformContext: PlatformContext,
        coroutineContext: CoroutineContext
    ) -> ComposeScene
) {
    private val keyboardOverlapHeightState: MutableState<Dp> = mutableStateOf(0.dp)
    private var _layout: SceneLayout = SceneLayout.Undefined
    private var constraints: List<NSLayoutConstraint> = emptyList()
        set(value) {
            if (field.isNotEmpty()) {
                NSLayoutConstraint.deactivateConstraints(field)
            }
            field = value
            NSLayoutConstraint.activateConstraints(value)
        }

    private val viewConfiguration: ViewConfiguration =
        object : ViewConfiguration by EmptyViewConfiguration {
            override val touchSlop: Float
                get() = with(density) {
                    // this value is originating from iOS 16 drag behavior reverse engineering
                    10.dp.toPx()
                }
        }

    private val scene: ComposeScene by lazy {
        composeSceneFactory(
            ::onComposeSceneInvalidate,
            IOSPlatformContext(),
            coroutineContext,
        )
    }
    var compositionLocalContext
        get() = scene.compositionLocalContext
        set(value) {
            scene.compositionLocalContext = value
        }

    val focusManager get() = scene.focusManager

    private val renderingView by lazy {
        renderingUIViewFactory(interopContext, renderDelegate)
    }

    private val applicationForegroundStateListener = ApplicationForegroundStateListener { isForeground ->
        // Sometimes the application can trigger animation and go background before the animation is
        // finished. The scheduled GPU work is performed, but no presentation can be done, causing
        // mismatch between visual state and application state. This can be fixed by forcing
        // a redraw when app returns to foreground, which will ensure that the visual state is in
        // sync with the application state even if such sequence of events took a place.
        renderingView.needRedraw()
    }

    /**
     * view, that contains [interopViewContainer] and [interactionView] and is added to [container]
     */
    private val rootView = ComposeSceneMediatorRootUIView()

    /**
     * Container for UIKitView and UIKitViewController
     */
    private val interopViewContainer = UIKitInteropContainer()

    private val interactionBounds: IntRect get() {
        val boundsLayout = _layout as? SceneLayout.Bounds
        return boundsLayout?.interactionBounds ?: renderingViewBoundsInPx
    }

    private val interactionView by lazy {
        InteractionUIView(
            keyboardEventHandler = keyboardEventHandler,
            touchesDelegate = touchesDelegate,
            updateTouchesCount = { count ->
                val needHighFrequencyPolling = count > 0
                renderingView.redrawer.needsProactiveDisplayLink = needHighFrequencyPolling
            },
            inBounds = { point ->
                val positionInContainer = point.useContents {
                    asDpOffset().toOffset(container.systemDensity).round()
                }
                interactionBounds.contains(positionInContainer)
            }
        )
    }

    private val interopContext: UIKitInteropContext by lazy {
        UIKitInteropContext(
            requestRedraw = ::onComposeSceneInvalidate
        )
    }

    @OptIn(ExperimentalComposeApi::class)
    private val semanticsOwnerListener by lazy {
        SemanticsOwnerListenerImpl(
            rootView,
            coroutineContext,
            getAccessibilitySyncOptions = {
                configuration.accessibilitySyncOptions
            },
            convertToAppWindowCGRect = { rect, window ->
               windowContext.convertWindowRect(rect, window)
                   .toDpRect(Density(window.screen.scale.toFloat()))
                   .asCGRect()
            },
            performEscape = {
                val down = onKeyboardEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown))
                val up = onKeyboardEvent(KeyEvent(Key.Escape, KeyEventType.KeyUp))

                down || up
            }
        )
    }

    private val keyboardManager by lazy {
        ComposeSceneKeyboardOffsetManager(
            configuration = configuration,
            keyboardOverlapHeightState = keyboardOverlapHeightState,
            viewProvider = { viewForKeyboardOffsetTransform },
            composeSceneMediatorProvider = { this },
            onComposeSceneOffsetChanged = { offset ->
                viewForKeyboardOffsetTransform.layer.setAffineTransform(CGAffineTransformMakeTranslation(0.0, -offset))
                scene.invalidatePositionInWindow()
            }
        )
    }

    private val keyboardEventHandler: KeyboardEventHandler by lazy {
        object : KeyboardEventHandler {
            override fun onKeyboardEvent(event: KeyEvent) {
                this@ComposeSceneMediator.onKeyboardEvent(event)
            }
        }
    }

    private val uiKitTextInputService: UIKitTextInputService by lazy {
        UIKitTextInputService(
            updateView = {
                renderingView.setNeedsDisplay() // redraw on next frame
                CATransaction.flush() // clear all animations
            },
            rootViewProvider = { rootView },
            densityProvider = { rootView.systemDensity },
            viewConfiguration = viewConfiguration,
            focusStack = focusStack,
            keyboardEventHandler = keyboardEventHandler
        ).also {
            KeyboardVisibilityListener.initialize()
        }
    }

    private val touchesDelegate: InteractionUIView.Delegate by lazy {
        object : InteractionUIView.Delegate {
            override fun pointInside(point: CValue<CGPoint>, event: UIEvent?): Boolean =
                point.useContents {
                    val position = this.asDpOffset().toOffset(density)
                    !scene.hitTestInteropView(position)
                }

            override fun onTouchesEvent(view: UIView, event: UIEvent, phase: UITouchesEventPhase) {
                scene.sendPointerEvent(
                    eventType = phase.toPointerEventType(),
                    pointers = event.touchesForView(view)?.map {
                        val touch = it as UITouch
                        val id = touch.hashCode().toLong()
                        val position = touch.offsetInView(view, density.density)
                        ComposeScenePointer(
                            id = PointerId(id),
                            position = position,
                            pressed = touch.isPressed,
                            type = PointerType.Touch,
                            pressure = touch.force.toFloat(),
                            historical = event.historicalChangesForTouch(
                                touch,
                                view,
                                density.density
                            )
                        )
                    } ?: emptyList(),
                    timeMillis = (event.timestamp * 1e3).toLong(),
                    nativeEvent = event
                )
            }
        }
    }

    private val renderDelegate by lazy {
        RenderingUIViewDelegateImpl(
            scene = scene,
            sceneOffset = { -renderingViewBoundsInPx.topLeft.toOffset() }
        )
    }

    var density by scene::density
    var layoutDirection by scene::layoutDirection

    private var onAttachedToWindow: (() -> Unit)? = null
    private fun runOnceViewAttached(block: () -> Unit) {
        if (renderingView.window == null) {
            onAttachedToWindow = {
                onAttachedToWindow = null
                block()
            }
        } else {
            block()
        }
    }

    fun hitTestInteractionView(point: CValue<CGPoint>, withEvent: UIEvent?): UIView? =
        interactionView.hitTest(point, withEvent)

    init {
        renderingView.onAttachedToWindow = {
            renderingView.onAttachedToWindow = null
            viewWillLayoutSubviews()
            this.onAttachedToWindow?.invoke()
            focusStack?.pushAndFocus(interactionView)
        }

        rootView.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(rootView)
        NSLayoutConstraint.activateConstraints(
            getConstraintsToFillParent(rootView, container)
        )

        interopViewContainer.containerView.translatesAutoresizingMaskIntoConstraints = false
        rootView.addSubview(interopViewContainer.containerView)
        NSLayoutConstraint.activateConstraints(
            getConstraintsToFillParent(interopViewContainer.containerView, rootView)
        )

        interactionView.translatesAutoresizingMaskIntoConstraints = false
        rootView.addSubview(interactionView)
        NSLayoutConstraint.activateConstraints(
            getConstraintsToFillParent(interactionView, rootView)
        )
        // FIXME: interactionView might be smaller than renderingView (shadows etc)
        interactionView.addSubview(renderingView)
    }

    fun setContent(content: @Composable () -> Unit) {
        runOnceViewAttached {
            scene.setContent {
                /**
                 * TODO isReadyToShowContent it is workaround we need to fix.
                 *  https://github.com/JetBrains/compose-multiplatform-core/pull/861
                 *  Density problem already was fixed.
                 *  But there are still problem with safeArea.
                 *  Elijah founded possible solution:
                 *   https://developer.apple.com/documentation/uikit/uiviewcontroller/4195485-viewisappearing
                 *   It is public for iOS 17 and hope back ported for iOS 13 as well (but we need to check)
                 */
                if (renderingView.isReadyToShowContent.value) {
                    ProvideComposeSceneMediatorCompositionLocals {
                        interopViewContainer.TrackInteropContainer(
                            content = content
                        )
                    }
                }
            }
        }
    }

    private val safeAreaState: MutableState<PlatformInsets> by lazy {
        //TODO It calc 0,0,0,0 on initialization
        mutableStateOf(calcSafeArea())
    }
    private val layoutMarginsState: MutableState<PlatformInsets> by lazy {
        //TODO It calc 0,0,0,0 on initialization
        mutableStateOf(calcLayoutMargin())
    }

    fun viewSafeAreaInsetsDidChange() {
        safeAreaState.value = calcSafeArea()
        layoutMarginsState.value = calcLayoutMargin()
    }

    @OptIn(InternalComposeApi::class)
    @Composable
    private fun ProvideComposeSceneMediatorCompositionLocals(content: @Composable () -> Unit) =
        CompositionLocalProvider(
            LocalUIKitInteropContext provides interopContext,
            LocalUIKitInteropContainer provides interopViewContainer,
            LocalKeyboardOverlapHeight provides keyboardOverlapHeightState.value,
            LocalSafeArea provides safeAreaState.value,
            LocalLayoutMargins provides layoutMarginsState.value,
            content = content
        )

    fun dispose() {
        uiKitTextInputService.stopInput()
        applicationForegroundStateListener.dispose()
        focusStack?.popUntilNext(renderingView)
        keyboardManager.stop()
        renderingView.dispose()
        interactionView.dispose()
        rootView.removeFromSuperview()
        interactionView.removeFromSuperview()
        renderingView.removeFromSuperview()
        scene.close()
        // After scene is disposed all UIKit interop actions can't be deferred to be synchronized with rendering
        // Thus they need to be executed now.
        interopContext.retrieve().actions.forEach { it.invoke() }
    }

    private fun onComposeSceneInvalidate() = renderingView.needRedraw()

    fun setLayout(value: SceneLayout) {
        _layout = value
        when (value) {
            SceneLayout.UseConstraintsToFillContainer -> {
                renderingView.setFrame(CGRectZero.readValue())
                renderingView.translatesAutoresizingMaskIntoConstraints = false
                constraints = getConstraintsToFillParent(renderingView, interactionView)
            }

            is SceneLayout.UseConstraintsToCenter -> {
                renderingView.setFrame(CGRectZero.readValue())
                renderingView.translatesAutoresizingMaskIntoConstraints = false
                constraints =
                    getConstraintsToCenterInParent(renderingView, interactionView, value.size)
            }

            is SceneLayout.Bounds -> {
                val density = container.systemDensity.density
                renderingView.translatesAutoresizingMaskIntoConstraints = true
                renderingView.setFrame(
                    with(value.renderBounds) {
                        CGRectMake(
                            x = left.toDouble() / density,
                            y = top.toDouble() / density,
                            width = width.toDouble() / density,
                            height = height.toDouble() / density
                        )
                    }
                )
                constraints = emptyList()
            }

            is SceneLayout.Undefined -> error("setLayout, SceneLayout.Undefined")
        }
    }

    fun viewWillLayoutSubviews() {
        val density = container.systemDensity
        scene.density = density

        // TODO: it should be updated on any container size change
        val boundsInPx = container.bounds.useContents {
            with(density) {
                asDpRect().toRect()
            }
        }
        scene.size = IntSize(
            width = boundsInPx.width.roundToInt(),
            height = boundsInPx.height.roundToInt()
        )
    }

    private fun calcSafeArea(): PlatformInsets =
        container.safeAreaInsets.useContents {
            PlatformInsets(
                left = left.dp,
                top = top.dp,
                right = right.dp,
                bottom = bottom.dp,
            )
        }

    private fun calcLayoutMargin(): PlatformInsets =
        container.directionalLayoutMargins.useContents {
            PlatformInsets(
                left = leading.dp, // TODO: Check RTL support
                top = top.dp,
                right = trailing.dp, // TODO: Check RTL support
                bottom = bottom.dp,
            )
        }

    private val renderingViewBoundsInPx: IntRect get() = with(container.systemDensity) {
        renderingView.frame.useContents { asDpRect().toRect().roundToIntRect() }
    }

    fun viewWillTransitionToSize(
        targetSize: CValue<CGSize>,
        coordinator: UIViewControllerTransitionCoordinatorProtocol
    ) {
        if (_layout is SceneLayout.Bounds) {
            //TODO Add logic to SceneLayout.Bounds too
            return
        }

        val startSnapshotView = renderingView.snapshotViewAfterScreenUpdates(false) ?: return
        startSnapshotView.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(startSnapshotView)
        targetSize.useContents {
            NSLayoutConstraint.activateConstraints(
                listOf(
                    startSnapshotView.widthAnchor.constraintEqualToConstant(height),
                    startSnapshotView.heightAnchor.constraintEqualToConstant(width),
                    startSnapshotView.centerXAnchor.constraintEqualToAnchor(container.centerXAnchor),
                    startSnapshotView.centerYAnchor.constraintEqualToAnchor(container.centerYAnchor)
                )
            )
        }

        renderingView.isForcedToPresentWithTransactionEveryFrame = true

        setLayout(SceneLayout.UseConstraintsToCenter(size = targetSize))
        renderingView.transform = coordinator.targetTransform

        coordinator.animateAlongsideTransition(
            animation = {
                startSnapshotView.alpha = 0.0
                startSnapshotView.transform = CGAffineTransformInvert(coordinator.targetTransform)
                renderingView.transform = CGAffineTransformIdentity.readValue()
            },
            completion = {
                startSnapshotView.removeFromSuperview()
                setLayout(SceneLayout.UseConstraintsToFillContainer)
                renderingView.isForcedToPresentWithTransactionEveryFrame = false
            }
        )
    }

    fun sceneDidAppear() {
        keyboardManager.start()
    }

    fun sceneWillDisappear() {
        keyboardManager.stop()
    }

    fun getViewHeight(): Double = renderingView.frame.useContents {
        size.height
    }

    private var _onPreviewKeyEvent: (KeyEvent) -> Boolean = { false }
    private var _onKeyEvent: (KeyEvent) -> Boolean = { false }
    fun setKeyEventListener(
        onPreviewKeyEvent: ((KeyEvent) -> Boolean)?,
        onKeyEvent: ((KeyEvent) -> Boolean)?
    ) {
        this._onPreviewKeyEvent = onPreviewKeyEvent ?: { false }
        this._onKeyEvent = onKeyEvent ?: { false }
    }

    private fun onKeyboardEvent(keyEvent: KeyEvent): Boolean =
        uiKitTextInputService.onPreviewKeyEvent(keyEvent) // TODO: fix redundant call
            || _onPreviewKeyEvent(keyEvent)
            || scene.sendKeyEvent(keyEvent)
            || _onKeyEvent(keyEvent)

    @OptIn(ExperimentalComposeApi::class)
    private var viewForKeyboardOffsetTransform = if (configuration.platformLayers) {
        rootView
    } else {
        container
    }

    private inner class IOSPlatformContext : PlatformContext by PlatformContext.Empty {
        override val windowInfo: WindowInfo get() = windowContext.windowInfo

        override fun calculatePositionInWindow(localPosition: Offset): Offset =
            windowContext.calculatePositionInWindow(viewForKeyboardOffsetTransform, localPosition)

        override fun calculateLocalPosition(positionInWindow: Offset): Offset =
            windowContext.calculateLocalPosition(viewForKeyboardOffsetTransform, positionInWindow)

        override val measureDrawLayerBounds get() = this@ComposeSceneMediator.measureDrawLayerBounds
        override val viewConfiguration get() = this@ComposeSceneMediator.viewConfiguration
        override val inputModeManager = DefaultInputModeManager(InputMode.Touch)
        override val textInputService get() = this@ComposeSceneMediator.uiKitTextInputService
        override val textToolbar get() = this@ComposeSceneMediator.uiKitTextInputService
        override val semanticsOwnerListener get() = this@ComposeSceneMediator.semanticsOwnerListener
    }
}

internal fun getConstraintsToFillParent(view: UIView, parent: UIView) =
    listOf(
        view.leftAnchor.constraintEqualToAnchor(parent.leftAnchor),
        view.rightAnchor.constraintEqualToAnchor(parent.rightAnchor),
        view.topAnchor.constraintEqualToAnchor(parent.topAnchor),
        view.bottomAnchor.constraintEqualToAnchor(parent.bottomAnchor)
    )

private fun getConstraintsToCenterInParent(
    view: UIView,
    parentView: UIView,
    size: CValue<CGSize>,
) = size.useContents {
    listOf(
        view.centerXAnchor.constraintEqualToAnchor(parentView.centerXAnchor),
        view.centerYAnchor.constraintEqualToAnchor(parentView.centerYAnchor),
        view.widthAnchor.constraintEqualToConstant(width),
        view.heightAnchor.constraintEqualToConstant(height)
    )
}

private fun UITouchesEventPhase.toPointerEventType(): PointerEventType =
    when (this) {
        UITouchesEventPhase.BEGAN -> PointerEventType.Press
        UITouchesEventPhase.MOVED -> PointerEventType.Move
        UITouchesEventPhase.ENDED -> PointerEventType.Release
        UITouchesEventPhase.CANCELLED -> PointerEventType.Release
    }

private fun UIEvent.historicalChangesForTouch(
    touch: UITouch,
    view: UIView,
    density: Float
): List<HistoricalChange> {
    val touches = coalescedTouchesForTouch(touch) ?: return emptyList()

    return if (touches.size > 1) {
        // subList last index is exclusive, so the last touch in the list is not included
        // because it's the actual touch for which coalesced touches were requested
        touches.dropLast(1).map {
            val historicalTouch = it as UITouch
            val position = historicalTouch.offsetInView(view, density)
            HistoricalChange(
                uptimeMillis = (historicalTouch.timestamp * 1e3).toLong(),
                position = position,
                originalEventPosition = position
            )
        }
    } else {
        emptyList()
    }
}

private val UITouch.isPressed
    get() = when (phase) {
        UITouchPhase.UITouchPhaseEnded, UITouchPhase.UITouchPhaseCancelled -> false
        else -> true
    }

private fun UITouch.offsetInView(view: UIView, density: Float): Offset =
    locationInView(view).useContents {
        Offset(x.toFloat() * density, y.toFloat() * density)
    }
