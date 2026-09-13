package com.coolhimanhub.lordsgatheringassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.Text.TextBlock
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * V54: compact production gathering service.
 *
 * Flow:
 * screenshot -> RSS detection + authoritative viewport OCR ->
 * cross-viewport calibration -> isometric tile mapping ->
 * verified coverage sweep -> candidate tap -> live tile-panel verification -> Gather.
 *
 * A coverage swipe never advances the logical route until a later screenshot
 * proves that the authoritative game X/Y changed. Temporary OCR misses reuse
 * only the last confirmed coordinate and can never fabricate movement.
 * AUTO GATHER remains blocked until the grid mapper is LOCKED and the tile
 * panel explicitly confirms Gather availability.
 */
class GatheringAccessibilityService : AccessibilityService() {
    private val handler=Handler(Looper.getMainLooper())
    private val analysisExecutor=Executors.newSingleThreadExecutor()
    private val textRecognizer:TextRecognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val viewportTracker=MapViewportTracker()
    private val sweepController=CoverageSweepController()
    private val coordinateMapper=GameCoordinateMapper()

    @Volatile private var running=false
    @Volatile private var screenshotInProgress=false
    @Volatile private var serviceAlive=true
    @Volatile private var actionInProgress=false
    @Volatile private var sweepInProgress=false
    @Volatile private var autoGatheringActive=false

    private var overlayView:LinearLayout?=null
    private var overlayParams:WindowManager.LayoutParams?=null
    private var windowManager:WindowManager?=null
    private var infoText:TextView?=null
    private var startStopButton:Button?=null
    private var gatherButton:Button?=null
    private var detailsButton:Button?=null
    private var detailsExpanded=false
    private var scanCount=0

    private lateinit var screenAnalyzer:ScreenAnalyzer

    private val scanInterval=2200L
    private val panelWait=650L
    private val sweepWait=650L
    private val minimumConfidence=68
    private val maximumDisplayedTargets=12
    private val seenCandidates=LinkedHashSet<String>()

    private companion object {
        const val OVERLAY_X=24
        const val OVERLAY_Y=70
        const val OVERLAY_WIDTH=310
        const val OVERLAY_MAX_X=420
        const val OVERLAY_MAX_Y=420
    }

    override fun onServiceConnected(){
        super.onServiceConnected();serviceAlive=true
        try{
            windowManager=getSystemService(WINDOW_SERVICE) as WindowManager
            screenAnalyzer=ScreenAnalyzer()
            viewportTracker.reset();sweepController.reset();coordinateMapper.reset()
            handler.post{if(serviceAlive)showFloatingControl()}
        }catch(_:Exception){
            safeStatus("V54 service ready\nOverlay retrying...")
            handler.postDelayed({if(serviceAlive)recreateOverlay()},1000)
        }
    }

    override fun onAccessibilityEvent(event:AccessibilityEvent?){ }
    override fun onInterrupt(){stopAutomation();stopAutoGathering()}

    private fun showFloatingControl(){
        if(!serviceAlive||overlayView!=null)return
        val wm=windowManager?:return
        val container=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(8,7,8,7)
            setBackgroundColor(Color.rgb(52,52,52))
        }

        val title=TextView(this).apply{
            text="Lords Assistant  V54"
            textSize=18f
            setTextColor(Color.WHITE)
            gravity=Gravity.CENTER
            setPadding(4,2,4,7)
            isClickable=true
            isLongClickable=false
        }

        val startButton=Button(this).apply{
            text="▶  START SCAN"
            textSize=14f
            minHeight=0
            minimumHeight=0
            setOnClickListener{try{if(running)stopAutomation()else startAutomation()}catch(e:Exception){safeStatus("Button error: ${e.javaClass.simpleName}")}}
        }
        val scanBtn=Button(this).apply{
            text="🔍  ONE SCAN"
            textSize=14f
            minHeight=0
            minimumHeight=0
            setOnClickListener{try{scanScreen(false)}catch(e:Exception){safeStatus("Scan error: ${e.javaClass.simpleName}")}}
        }
        val gatherBtn=Button(this).apply{
            text="⚔  AUTO GATHER"
            textSize=14f
            minHeight=0
            minimumHeight=0
            setOnClickListener{try{if(autoGatheringActive)stopAutoGathering()else startAutoGathering()}catch(e:Exception){safeStatus("Gather error: ${e.javaClass.simpleName}")}}
        }
        val detailBtn=Button(this).apply{
            text="DETAILS ▾"
            textSize=11f
            minHeight=0
            minimumHeight=0
            setOnClickListener{
                detailsExpanded=!detailsExpanded
                text=if(detailsExpanded)"DETAILS ▴"else"DETAILS ▾"
                updateStatusFromState()
            }
        }
        val info=TextView(this).apply{
            text="READY\nGrid: LEARNING\nMove verification: ON"
            textSize=10.5f
            setTextColor(Color.WHITE)
            gravity=Gravity.LEFT
            setPadding(6,4,6,2)
            setLineSpacing(0f,1.05f)
        }

        infoText=info;startStopButton=startButton;gatherButton=gatherBtn;detailsButton=detailBtn
        container.addView(title,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,48))
        container.addView(startButton,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,50))
        container.addView(scanBtn,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,50))
        container.addView(gatherBtn,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,50))
        container.addView(detailBtn,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,38))
        val scroll=ScrollView(this).apply{isFillViewport=false;setPadding(0,2,0,0)}
        scroll.addView(info,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT))
        container.addView(scroll,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,220))

        val params=WindowManager.LayoutParams(
            OVERLAY_WIDTH,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity=Gravity.TOP or Gravity.START
        params.x=OVERLAY_X;params.y=OVERLAY_Y;overlayParams=params

        var downX=0f;var downY=0f;var startX=0;var startY=0
        title.setOnTouchListener{_,event->
            when(event.actionMasked){
                MotionEvent.ACTION_DOWN->{downX=event.rawX;downY=event.rawY;startX=params.x;startY=params.y;true}
                MotionEvent.ACTION_MOVE->{
                    params.x=(startX+(event.rawX-downX)).toInt().coerceIn(0,OVERLAY_MAX_X)
                    params.y=(startY+(event.rawY-downY)).toInt().coerceIn(0,OVERLAY_MAX_Y)
                    try{wm.updateViewLayout(container,params)}catch(_:Exception){}
                    true
                }
                MotionEvent.ACTION_UP->true
                else->false
            }
        }

        try{
            wm.addView(container,params)
            overlayView=container
            safeStatus("READY\nGrid: LEARNING\nMove verification: ON")
        }catch(_:Exception){
            overlayView=null;overlayParams=null;infoText=null;startStopButton=null;gatherButton=null;detailsButton=null
            handler.postDelayed({if(serviceAlive)recreateOverlay()},1000)
        }
    }

    private fun recreateOverlay(){if(!serviceAlive||overlayView!=null)return;showFloatingControl()}

    /** V54: the overlay is movable; never snap it back during scanning. */
    private fun pinOverlay(){ }

    private fun startAutomation(){
        if(running||!serviceAlive)return
        running=true;scanCount=0;seenCandidates.clear();viewportTracker.reset();sweepController.reset();coordinateMapper.reset();sweepInProgress=false
        updateStartStopButton();safeStatus("SCANNING\nEstablishing authoritative X/Y...\nGrid calibration: LEARNING")
        handler.removeCallbacks(scanRunnable);handler.postDelayed(scanRunnable,350)
    }

    private fun stopAutomation(){
        running=false;handler.removeCallbacks(scanRunnable);handler.removeCallbacks(sweepRunnable);sweepInProgress=false
        updateStartStopButton();safeStatus("STOPPED\nCoverage paused\nAuto Gather: ${if(autoGatheringActive)"ACTIVE"else"IDLE"}")
    }

    private fun startAutoGathering(){
        if(autoGatheringActive||!serviceAlive)return
        autoGatheringActive=true;updateGatherButton();safeStatus("GATHER MODE ARMED\nWaiting for Grid LOCK + verified tile panel")
        if(!running)startAutomation()
    }

    private fun stopAutoGathering(){
        autoGatheringActive=false;updateGatherButton();safeStatus("AUTO GATHER STOPPED\nNo further Gather action will be sent")
    }

    private val scanRunnable=object:Runnable{
        override fun run(){
            if(!running||!serviceAlive)return
            try{scanScreen(true)}catch(e:Exception){safeStatus("Scan exception: ${e.javaClass.simpleName}")}
            if(running&&serviceAlive)handler.postDelayed(this,scanInterval)
        }
    }

    private fun scanScreen(moveAfterScan:Boolean){
        if(!serviceAlive||Build.VERSION.SDK_INT<Build.VERSION_CODES.R)return
        if(screenshotInProgress||actionInProgress||sweepInProgress)return
        screenshotInProgress=true;scanCount++;val thisScan=scanCount
        safeStatus("SCANNING #$thisScan\nCapturing map + viewport...")
        try{
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY,mainExecutor,object:TakeScreenshotCallback{
                override fun onSuccess(screenshot:ScreenshotResult){
                    if(!serviceAlive){try{screenshot.hardwareBuffer.close()}catch(_:Exception){};screenshotInProgress=false;return}
                    processScreenshot(screenshot,thisScan,moveAfterScan)
                }
                override fun onFailure(errorCode:Int){screenshotInProgress=false;safeStatus("SCAN #$thisScan\nCapture failed: $errorCode")}
            })
        }catch(e:Exception){screenshotInProgress=false;safeStatus("SCAN #$thisScan\nCapture exception: ${e.javaClass.simpleName}")}
    }

    private fun processScreenshot(screenshot:ScreenshotResult,thisScan:Int,moveAfterScan:Boolean){
        try{
            val hb=try{Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer,screenshot.colorSpace)}catch(_:Exception){null}
            if(hb==null){screenshotInProgress=false;try{screenshot.hardwareBuffer.close()}catch(_:Exception){};return}
            val bitmap=try{hb.copy(Bitmap.Config.ARGB_8888,false)}catch(_:Exception){null}
            try{hb.recycle()}catch(_:Exception){};try{screenshot.hardwareBuffer.close()}catch(_:Exception){}
            if(bitmap==null){screenshotInProgress=false;return}
            analysisExecutor.execute{
                try{
                    if(!serviceAlive){recycleBitmap(bitmap);return@execute}
                    val detections=screenAnalyzer.analyzeScreenshot(bitmap)
                    recognizeViewport(thisScan,moveAfterScan,detections,bitmap)
                }catch(e:Exception){safeStatus("Analysis error: ${e.javaClass.simpleName}\n${e.message?:"unknown"}");screenshotInProgress=false;recycleBitmap(bitmap)}
            }
        }catch(e:Exception){screenshotInProgress=false;safeStatus("Screenshot processing error: ${e.javaClass.simpleName}")}
    }

    private fun recognizeViewport(scanNumber:Int,moveAfterScan:Boolean,detections:List<ScreenAnalyzer.RssDetection>,bitmap:Bitmap){
        val viewport=viewportTracker.parse("")
        if(viewport==null){
            screenshotInProgress=false
            safeStatus("SCAN #$scanNumber\nNo authoritative X/Y yet\nCoverage WAITING\nRetrying OCR...")
            recycleBitmap(bitmap)
            return
        }
        val changed=viewportTracker.update(viewport)
        val screenPoints=detections.map{it.centerX to it.centerY}
        coordinateMapper.observe(viewport,screenPoints,bitmap.width,bitmap.height)
        sweepController.onViewportObserved(changed,viewportTracker.hasAuthoritativeViewport())
        handleScanResult(scanNumber,detections,viewport,changed,moveAfterScan,bitmap.width,bitmap.height)
        screenshotInProgress=false;recycleBitmap(bitmap)
    }

    private fun handleScanResult(scanNumber:Int,detections:List<ScreenAnalyzer.RssDetection>,viewport:MapViewportTracker.Viewport,viewportChanged:Boolean,moveAfterScan:Boolean,screenWidth:Int,screenHeight:Int){
        val calibration=coordinateMapper.calibration()
        val targets=detections.filter{it.confidence>=minimumConfidence}.map{d->
            val game=coordinateMapper.map(d.centerX,d.centerY,viewport.x,viewport.y,screenWidth,screenHeight)
            val residual=coordinateMapper.tileResidual(d.centerX,d.centerY,game,viewport.x,viewport.y,screenWidth,screenHeight)
            TargetWithGame(d,game,residual)
        }.filter{it.residual<=28f}.sortedWith(compareByDescending<TargetWithGame>{it.detection.confidence}.thenBy{it.residual})

        val displayCount=min(targets.size,maximumDisplayedTargets)
        val gridState=if(calibration.ready)"LOCKED"else"LEARNING"
        val movementState=when{
            viewportChanged->"MOVED"
            sweepController.needsViewportChange()->"WAITING FOR MOVE (${sweepController.unchangedCount()})"
            else->"READY"
        }
        val status=StringBuilder()
            .append("Scan #$scanNumber  X:${viewport.x} Y:${viewport.y}\n")
            .append("Viewport: ${if(viewportChanged)"NEW"else"SAME"}  Move: $movementState\n")
            .append("Grid: $gridState  Q:${calibration.quality}%  S:${calibration.samples}\n")
            .append("Tile: ${"%.1f".format(calibration.halfTileW)}/${"%.1f".format(calibration.halfTileH)} px\n")
            .append("RSS: $displayCount validated\n")

        if(detailsExpanded){
            status.append("\nGame coordinates:\n")
            for(i in 0 until displayCount){
                val t=targets[i]
                val mapState=if(calibration.ready)"OK"else"CAL"
                status.append("${i+1}. X:${t.game.x} Y:${t.game.y} [${t.detection.confidence}%/$mapState R:${"%.1f".format(t.residual)}px]\n")
            }
            if(displayCount==0)status.append("No validated RSS candidates\n")
        }

        if(autoGatheringActive&&viewportChanged&&!actionInProgress){
            if(!calibration.ready){
                status.append("\nGATHER WAITING\nGrid calibration not locked\nNo troop action authorized")
                safeStatus(status.toString())
            }else{
                val fresh=targets.filter{markCandidateSeen(it.game)}
                if(fresh.isNotEmpty())probeBestCandidate(fresh)
                else safeStatus(status.append("\nNo new calibrated candidates in this viewport").toString())
            }
        }else{
            safeStatus(status.toString())
        }

        if(moveAfterScan&&running&&!actionInProgress&&!sweepInProgress){
            if(sweepController.canIssueNextSwipe() || sweepController.recoverySwipe()!=null){
                scheduleCoverageSweep()
            }
        }
    }

    private data class TargetWithGame(
        val detection:ScreenAnalyzer.RssDetection,
        val game:GameCoordinateMapper.GameLocation,
        val residual:Float
    )

    private fun markCandidateSeen(game:GameCoordinateMapper.GameLocation):Boolean{
        return seenCandidates.add("${game.x}:${game.y}")
    }

    private fun scheduleCoverageSweep(){
        if(!running||!serviceAlive||actionInProgress||sweepInProgress)return
        sweepInProgress=true
        handler.postDelayed(sweepRunnable,sweepWait)
    }

    private val sweepRunnable=Runnable{
        if(!running||!serviceAlive||actionInProgress){sweepInProgress=false;return@Runnable}
        val recovery=sweepController.recoverySwipe()
        val plan=recovery?:sweepController.nextSwipe()
        if(recovery==null && sweepController.needsViewportChange()){
            sweepInProgress=false
            safeStatus("WAITING FOR MAP MOVEMENT\nLast swipe not yet verified\nNo new swipe issued")
            return@Runnable
        }
        safeStatus(if(recovery!=null)
            "RECOVERY SWEEP\n${plan.direction}\nWaiting for authoritative X/Y change..."
            else
            "COVERAGE SWEEP\n${plan.direction}\nWaiting for authoritative X/Y change...")
        if(!swipeMap(plan.start.x,plan.start.y,plan.end.x,plan.end.y,plan.durationMs)){
            sweepController.markDispatchFailure(plan.direction)
            sweepInProgress=false
            safeStatus("COVERAGE SWIPE FAILED\nGesture rejected\nRoute not advanced")
        }else{
            sweepController.markSwipeIssued(plan.direction)
            handler.postDelayed({
                sweepInProgress=false
                if(running&&!actionInProgress)safeStatus("SWIPE SENT\nNext scan must prove X/Y movement")
            },plan.durationMs+180L)
        }
    }

    private fun probeBestCandidate(targets:List<TargetWithGame>){
        if(!coordinateMapper.calibration().ready){
            actionInProgress=false
            safeStatus("AUTO GATHER PAUSED\nGrid calibration is not LOCKED\nNo troop action taken")
            return
        }
        val candidate=targets.maxByOrNull{it.detection.confidence}?:return
        actionInProgress=true
        safeStatus("VERIFYING TARGET\nX:${candidate.game.x} Y:${candidate.game.y}\nResidual ${"%.1f".format(candidate.residual)} px\nOpening tile panel...")
        handler.post{
            if(!serviceAlive){actionInProgress=false;return@post}
            if(!tapAt(candidate.detection.centerX,candidate.detection.centerY)){
                actionInProgress=false;safeStatus("TARGET TAP FAILED\nNo Gather action sent");return@post
            }
            handler.postDelayed({capturePanelForVerification()},panelWait)
        }
    }

    private fun capturePanelForVerification(){
        if(!serviceAlive||Build.VERSION.SDK_INT<Build.VERSION_CODES.R){actionInProgress=false;return}
        try{
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY,mainExecutor,object:TakeScreenshotCallback{
                override fun onSuccess(screenshot:ScreenshotResult){
                    val hb=try{Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer,screenshot.colorSpace)}catch(_:Exception){null}
                    val bitmap=try{hb?.copy(Bitmap.Config.ARGB_8888,false)}catch(_:Exception){null}
                    try{hb?.recycle()}catch(_:Exception){};try{screenshot.hardwareBuffer.close()}catch(_:Exception){}
                    if(bitmap==null){actionInProgress=false;safeStatus("PANEL SCREENSHOT FAILED\nNo Gather action sent");return}
                    runPanelOcr(bitmap)
                }
                override fun onFailure(errorCode:Int){actionInProgress=false;safeStatus("PANEL CAPTURE FAILED: $errorCode\nNo Gather action sent")}
            })
        }catch(e:Exception){actionInProgress=false;safeStatus("PANEL CAPTURE ERROR\nNo Gather action sent")}
    }

    private fun runPanelOcr(bitmap:Bitmap){
        val image=InputImage.fromBitmap(bitmap,0)
        textRecognizer.process(image)
            .addOnSuccessListener{result->
                val verification=TilePanelVerifier.verify(result.text)
                if(!verification.safeToGather){actionInProgress=false;safeStatus("PANEL REJECTED\n${verification.reason}\nNo Gather action sent");return@addOnSuccessListener}
                safeStatus("PANEL VERIFIED\n${verification.type} L${verification.level}\nGather available")
                tapGatherFromOcr(result.textBlocks)
            }
            .addOnFailureListener{actionInProgress=false;safeStatus("PANEL OCR FAILED\nNo Gather action sent")}
            .addOnCompleteListener{recycleBitmap(bitmap)}
    }

    private fun tapGatherFromOcr(blocks:List<TextBlock>){
        val gatherElement=blocks.asSequence().flatMap{it.lines.asSequence()}.flatMap{it.elements.asSequence()}.firstOrNull{it.text.contains("gather",ignoreCase=true)}
        val rect=gatherElement?.boundingBox
        if(rect==null){actionInProgress=false;safeStatus("PANEL VERIFIED\nGather control not located\nNo action sent");return}
        val x=(rect.left+rect.right)/2;val y=(rect.top+rect.bottom)/2
        handler.postDelayed({
            if(!serviceAlive||!tapAt(x,y))safeStatus("GATHER TAP FAILED")else safeStatus("GATHER ACTION SENT\nTarget was panel-verified")
            actionInProgress=false
            if(running)scheduleCoverageSweep()
        },200)
    }

    private fun swipeMap(x1:Float,y1:Float,x2:Float,y2:Float,durationMs:Long):Boolean{
        if(Build.VERSION.SDK_INT<Build.VERSION_CODES.N)return false
        val path=Path().apply{moveTo(x1,y1);lineTo(x2,y2)}
        val gesture=GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,durationMs)).build()
        return try{dispatchGesture(gesture,null,null)}catch(_:Exception){false}
    }

    private fun tapAt(x:Int,y:Int):Boolean{
        if(Build.VERSION.SDK_INT<Build.VERSION_CODES.N)return false
        val path=Path().apply{moveTo(x.toFloat(),y.toFloat())}
        val gesture=GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,80)).build()
        return try{dispatchGesture(gesture,null,null)}catch(_:Exception){false}
    }

    private fun recycleBitmap(bitmap:Bitmap){try{if(!bitmap.isRecycled)bitmap.recycle()}catch(_:Exception){}}
    private fun updateStartStopButton(){handler.post{try{startStopButton?.text=if(running)"⏸  STOP SCAN"else"▶  START SCAN"}catch(_:Exception){}}}
    private fun updateGatherButton(){handler.post{try{gatherButton?.text=if(autoGatheringActive)"⏸  STOP GATHER"else"⚔  AUTO GATHER"}catch(_:Exception){}}}

    private fun updateStatusFromState(){
        val current=viewportTracker.current()
        val grid=if(coordinateMapper.calibration().ready)"LOCKED"else"LEARNING"
        val state=if(running)"SCANNING"else"READY"
        val movement=if(sweepController.needsViewportChange())"WAITING FOR MOVE"else"READY"
        val base=StringBuilder().append("$state\nGrid: $grid\nMove: $movement")
        current?.let{base.append("\nX:${it.x} Y:${it.y}")}
        if(detailsExpanded)base.append("\n\nV54\nVerified viewport gating\nPanel verification enabled")
        safeStatus(base.toString())
    }

    private fun safeStatus(status:String){handler.post{try{infoText?.text=status}catch(_:Exception){}}}

    override fun onDestroy(){
        serviceAlive=false;running=false;autoGatheringActive=false;actionInProgress=false;sweepInProgress=false
        handler.removeCallbacksAndMessages(null)
        try{screenAnalyzer.close()}catch(_:Exception){}
        try{analysisExecutor.shutdownNow()}catch(_:Exception){}
        try{textRecognizer.close()}catch(_:Exception){}
        try{overlayView?.let{windowManager?.removeView(it)}}catch(_:Exception){}
        overlayView=null;overlayParams=null;infoText=null;startStopButton=null;gatherButton=null;detailsButton=null
        super.onDestroy()
    }
}
