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
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import kotlin.math.min

/** V38: fast scanner with game-coordinate RSS reporting. */
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

    private var overlayView:LinearLayout?=null
    private var overlayParams:WindowManager.LayoutParams?=null
    private var windowManager:WindowManager?=null
    private var infoText:TextView?=null
    private var startStopButton:Button?=null
    private var scanButton:Button?=null
    private var gatherButton:Button?=null
    private var scanCount=0

    private lateinit var gatheringEngine:AutoGatheringEngine
    private lateinit var screenAnalyzer:ScreenAnalyzer
    @Volatile private var autoGatheringActive=false

    private val scanInterval=2200L
    private val panelWait=650L
    private val sweepWait=700L
    private val minimumConfidence=68
    private val maximumDisplayedTargets=20
    private val seenCandidates=LinkedHashSet<String>()

    override fun onServiceConnected(){
        super.onServiceConnected();serviceAlive=true
        try{
            windowManager=getSystemService(WINDOW_SERVICE) as WindowManager
            gatheringEngine=AutoGatheringEngine(this,handler)
            screenAnalyzer=ScreenAnalyzer()
            viewportTracker.reset();sweepController.reset()
            handler.post{if(serviceAlive)showFloatingControl()}
        }catch(_:Exception){
            safeStatus("V38 service ready\nOverlay retrying...")
            handler.postDelayed({if(serviceAlive)recreateOverlay()},1000)
        }
    }

    override fun onAccessibilityEvent(event:AccessibilityEvent?){ }
    override fun onInterrupt(){stopAutomation();stopAutoGathering()}

    private fun showFloatingControl(){
        if(!serviceAlive||overlayView!=null)return
        val wm=windowManager?:return
        val container=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setPadding(10,8,10,8);setBackgroundColor(Color.rgb(65,65,65))
        }
        val title=TextView(this).apply{
            text="Lords Assistant V38";textSize=16f;setTextColor(Color.WHITE);gravity=Gravity.CENTER;setPadding(8,4,8,8)
        }
        title.setOnTouchListener(object:View.OnTouchListener{
            private var startX=0f;private var startY=0f;private var startParamX=0;private var startParamY=0
            override fun onTouch(view:View?,event:MotionEvent):Boolean{
                val p=overlayParams?:return false
                when(event.actionMasked){
                    MotionEvent.ACTION_DOWN->{startX=event.rawX;startY=event.rawY;startParamX=p.x;startParamY=p.y;return true}
                    MotionEvent.ACTION_MOVE->{p.x=(startParamX+event.rawX-startX).toInt();p.y=(startParamY+event.rawY-startY).toInt();try{wm.updateViewLayout(container,p)}catch(_:Exception){};return true}
                    else->return true
                }
            }
        })
        val startButton=Button(this).apply{
            text="▶ SCAN";setOnClickListener{try{if(running)stopAutomation()else startAutomation()}catch(e:Exception){safeStatus("Button error: ${e.javaClass.simpleName}")}}
        }
        val scanBtn=Button(this).apply{
            text="🔍 ONE SCAN";setOnClickListener{try{scanScreen(false)}catch(e:Exception){safeStatus("Scan error: ${e.javaClass.simpleName}")}}
        }
        val gatherBtn=Button(this).apply{
            text="⚔ AUTO GATHER";setOnClickListener{try{if(autoGatheringActive)stopAutoGathering()else startAutoGathering()}catch(e:Exception){safeStatus("Gather error: ${e.javaClass.simpleName}")}}
        }
        val info=TextView(this).apply{
            text="V38 Scanner ready\nGame X/Y RSS locations\nFast focused viewport OCR\nResolution-aware map coverage\nOpened-panel verification required"
            textSize=10.5f;setTextColor(Color.WHITE);gravity=Gravity.LEFT;setPadding(6,5,6,2);setLineSpacing(0f,1.05f)
        }
        infoText=info;startStopButton=startButton;scanButton=scanBtn;gatherButton=gatherBtn
        container.addView(title);container.addView(startButton);container.addView(scanBtn);container.addView(gatherBtn)
        val scroll=ScrollView(this).apply{isFillViewport=false;setPadding(0,2,0,0)}
        scroll.addView(info,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT))
        container.addView(scroll,LinearLayout.LayoutParams(470,520))
        val params=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT)
        params.gravity=Gravity.TOP or Gravity.START;params.x=100;params.y=80;overlayParams=params
        try{wm.addView(container,params);overlayView=container;safeStatus("V38 Scanner ready\nGame X/Y RSS locations enabled\nResolution-aware coverage ready")}
        catch(_:Exception){overlayView=null;overlayParams=null;infoText=null;startStopButton=null;scanButton=null;gatherButton=null;handler.postDelayed({if(serviceAlive)recreateOverlay()},1000)}
    }

    private fun recreateOverlay(){if(!serviceAlive||overlayView!=null)return;showFloatingControl()}

    private fun startAutomation(){
        if(running||!serviceAlive)return
        running=true;scanCount=0;seenCandidates.clear();viewportTracker.reset();sweepController.reset();sweepInProgress=false
        updateStartStopButton();safeStatus("V38 AUTO SCAN started\nEstablishing map viewport...\nGame-coordinate reporting enabled")
        handler.removeCallbacks(scanRunnable);handler.postDelayed(scanRunnable,500)
    }

    private fun stopAutomation(){
        running=false;handler.removeCallbacks(scanRunnable);handler.removeCallbacks(sweepRunnable);sweepInProgress=false
        updateStartStopButton();safeStatus("SCAN STOPPED\nCoverage paused\nAuto-gather: ${if(autoGatheringActive)"ACTIVE"else"IDLE"}")
    }

    private fun startAutoGathering(){
        if(autoGatheringActive||!serviceAlive)return
        autoGatheringActive=true;updateGatherButton();gatheringEngine.startGathering();safeStatus("AUTO GATHER ACTIVE\nNew viewport -> candidate -> panel -> OCR -> verify")
        if(!running)startAutomation()
    }

    private fun stopAutoGathering(){
        autoGatheringActive=false;try{gatheringEngine.stopGathering()}catch(_:Exception){}
        updateGatherButton();safeStatus("AUTO GATHER STOPPED\nNo further Gather action will be sent")
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
        safeStatus("Scan #$thisScan\nCapturing map + focused viewport...")
        try{
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY,mainExecutor,object:TakeScreenshotCallback{
                override fun onSuccess(screenshot:ScreenshotResult){
                    if(!serviceAlive){try{screenshot.hardwareBuffer.close()}catch(_:Exception){};screenshotInProgress=false;return}
                    processScreenshot(screenshot,thisScan,moveAfterScan)
                }
                override fun onFailure(errorCode:Int){screenshotInProgress=false;safeStatus("Scan #$thisScan\nCapture failed: $errorCode")}
            })
        }catch(e:Exception){screenshotInProgress=false;safeStatus("Scan #$thisScan\nCapture exception: ${e.javaClass.simpleName}")}
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
        if(viewport==null){screenshotInProgress=false;safeStatus("Scan #$scanNumber\nViewport X/Y not readable\nCoverage sweep PAUSED");recycleBitmap(bitmap);return}
        val changed=viewportTracker.update(viewport);sweepController.onViewportObserved(changed)
        handleScanResult(scanNumber,detections,viewport,changed,moveAfterScan,bitmap.width,bitmap.height)
        screenshotInProgress=false;recycleBitmap(bitmap)
    }

    private fun handleScanResult(scanNumber:Int,detections:List<ScreenAnalyzer.RssDetection>,viewport:MapViewportTracker.Viewport,viewportChanged:Boolean,moveAfterScan:Boolean,screenWidth:Int,screenHeight:Int){
        val targets=detections.filter{it.confidence>=minimumConfidence}.map{d->
            val game=coordinateMapper.map(d.centerX,d.centerY,viewport.x,viewport.y,screenWidth,screenHeight)
            TargetWithGame(d,AutoGatheringEngine.RssTarget("RSS?",0,d.centerX,d.centerY,d.confidence,false,0,d.confidence,false,0),game)
        }.sortedByDescending{it.detection.confidence}
        val displayCount=min(targets.size,maximumDisplayedTargets)
        val status=StringBuilder().append("Scan #$scanNumber  View X:${viewport.x} Y:${viewport.y}\n")
            .append(if(viewportChanged)"NEW VIEWPORT\n"else"SAME VIEWPORT\n")
            .append("RSS locations (game coordinates):\n")
        for(i in 0 until displayCount){
            val t=targets[i]
            status.append("${i+1}. RSS @ X:${t.game.x} Y:${t.game.y} (${t.detection.confidence}%)\n")
        }
        if(displayCount==0)status.append("No validated RSS badge candidates\n")
        safeStatus(status.toString())

        if(autoGatheringActive&&viewportChanged&&!actionInProgress){
            val fresh=targets.filter{markCandidateSeen(viewport,it.target,it.game)}
            if(fresh.isNotEmpty())probeBestCandidate(fresh)
            else safeStatus(status.append("No new candidates in this viewport").toString())
        }
        if(moveAfterScan&&running&&!actionInProgress&&!sweepInProgress)scheduleCoverageSweep()
    }

    private data class TargetWithGame(val detection:ScreenAnalyzer.RssDetection,val target:AutoGatheringEngine.RssTarget,val game:GameCoordinateMapper.GameLocation)

    private fun markCandidateSeen(viewport:MapViewportTracker.Viewport,target:AutoGatheringEngine.RssTarget,game:GameCoordinateMapper.GameLocation):Boolean{
        val key="${game.x}:${game.y}"
        return seenCandidates.add(key)
    }

    private fun scheduleCoverageSweep(){
        if(!running||!serviceAlive||actionInProgress||sweepInProgress)return
        sweepInProgress=true;handler.postDelayed(sweepRunnable,sweepWait)
    }

    private val sweepRunnable=Runnable{
        if(!running||!serviceAlive||actionInProgress){sweepInProgress=false;return@Runnable}
        val plan=sweepController.recoverySwipe()?:sweepController.nextSwipe()
        safeStatus("Coverage sweep\n${plan.direction}\nWaiting for X/Y to change...")
        sweepController.markSwipeIssued(plan.direction)
        if(!swipeMap(plan.start.x,plan.start.y,plan.end.x,plan.end.y,plan.durationMs)){
            sweepInProgress=false;safeStatus("Coverage swipe failed\nNo new area accepted")
        }else{
            handler.postDelayed({sweepInProgress=false;if(running&&!actionInProgress)safeStatus("Swipe sent\nNext scan will validate X/Y change")},plan.durationMs+250L)
        }
    }

    private fun probeBestCandidate(targets:List<TargetWithGame>){
        val candidate=targets.maxByOrNull{it.detection.confidence}?:return
        actionInProgress=true;safeStatus("Opening NEW candidate\nGame location X:${candidate.game.x} Y:${candidate.game.y}\nWaiting for tile panel...")
        handler.post{
            if(!serviceAlive){actionInProgress=false;return@post}
            if(!tapAt(candidate.target.x,candidate.target.y)){actionInProgress=false;safeStatus("Candidate tap failed\nNo Gather action sent");return@post}
            handler.postDelayed({capturePanelForVerification(candidate.target)},panelWait)
        }
    }

    private fun capturePanelForVerification(candidate:AutoGatheringEngine.RssTarget){
        if(!serviceAlive||Build.VERSION.SDK_INT<Build.VERSION_CODES.R){actionInProgress=false;return}
        try{
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY,mainExecutor,object:TakeScreenshotCallback{
                override fun onSuccess(screenshot:ScreenshotResult){
                    val hb=try{Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer,screenshot.colorSpace)}catch(_:Exception){null}
                    val bitmap=try{hb?.copy(Bitmap.Config.ARGB_8888,false)}catch(_:Exception){null}
                    try{hb?.recycle()}catch(_:Exception){};try{screenshot.hardwareBuffer.close()}catch(_:Exception){ }
                    if(bitmap==null){actionInProgress=false;safeStatus("Panel screenshot failed\nNo Gather action sent");return}
                    runPanelOcr(bitmap,candidate)
                }
                override fun onFailure(errorCode:Int){actionInProgress=false;safeStatus("Panel capture failed: $errorCode\nNo Gather action sent")}
            })
        }catch(e:Exception){actionInProgress=false;safeStatus("Panel capture exception: ${e.javaClass.simpleName}\nNo Gather action sent")}
    }

    private fun runPanelOcr(bitmap:Bitmap,candidate:AutoGatheringEngine.RssTarget){
        val image=InputImage.fromBitmap(bitmap,0)
        textRecognizer.process(image)
            .addOnSuccessListener{result->
                val verification=TilePanelVerifier.verify(result.text)
                if(!verification.safeToGather){actionInProgress=false;safeStatus("Panel rejected\n${verification.reason}\nNo Gather action sent");return@addOnSuccessListener}
                safeStatus("Panel verified\n${verification.type} L${verification.level}\nGather available\nExecuting Gather")
                tapGatherFromOcr(result.textBlocks,bitmap)
            }
            .addOnFailureListener{actionInProgress=false;safeStatus("Panel OCR failed\nNo Gather action sent")}
            .addOnCompleteListener{recycleBitmap(bitmap)}
    }

    private fun tapGatherFromOcr(blocks:List<com.google.mlkit.vision.text.Text.TextBlock>,bitmap:Bitmap){
        val gatherElement=blocks.asSequence().flatMap{it.lines.asSequence()}.flatMap{it.elements.asSequence()}.firstOrNull{it.text.contains("gather",ignoreCase=true)}
        val rect=gatherElement?.boundingBox
        if(rect==null){actionInProgress=false;safeStatus("Panel verified but Gather control position was not found\nNo action sent");return}
        val x=(rect.left+rect.right)/2;val y=(rect.top+rect.bottom)/2
        handler.postDelayed({
            if(!serviceAlive||!tapAt(x,y))safeStatus("Gather tap failed")else safeStatus("Gather action sent\nTarget was panel-verified")
            gatheringEngine.clearCurrentTarget();actionInProgress=false
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
    private fun updateStartStopButton(){handler.post{try{startStopButton?.text=if(running)"⏸ STOP"else"▶ SCAN"}catch(_:Exception){}}}
    private fun updateGatherButton(){handler.post{try{gatherButton?.text=if(autoGatheringActive)"⏸ STOP GATHER"else"⚔ AUTO GATHER"}catch(_:Exception){}}}
    private fun safeStatus(status:String){handler.post{try{infoText?.text=status}catch(_:Exception){}}}

    override fun onDestroy(){
        serviceAlive=false;running=false;autoGatheringActive=false;actionInProgress=false;sweepInProgress=false
        handler.removeCallbacksAndMessages(null)
        try{gatheringEngine.stopGathering()}catch(_:Exception){}
        try{screenAnalyzer.close()}catch(_:Exception){}
        try{analysisExecutor.shutdownNow()}catch(_:Exception){}
        try{textRecognizer.close()}catch(_:Exception){}
        try{overlayView?.let{windowManager?.removeView(it)}}catch(_:Exception){}
        overlayView=null;overlayParams=null;infoText=null;startStopButton=null;scanButton=null;gatherButton=null
        super.onDestroy()
    }
}
