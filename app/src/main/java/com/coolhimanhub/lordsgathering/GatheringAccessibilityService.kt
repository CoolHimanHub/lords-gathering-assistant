package com.coolhimanhub.lordsgatheringassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min


class GatheringAccessibilityService :
    AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val textRecognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @Volatile private var running = false
    @Volatile private var screenshotInProgress = false
    @Volatile private var serviceAlive = true
    private var overlayView: LinearLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null
    private var infoText: TextView? = null
    private var startStopButton: Button? = null
    private var scanButton: Button? = null
    private var gatherButton: Button? = null
    private var scanCount = 0

    private lateinit var gatheringEngine: AutoGatheringEngine
    private lateinit var screenAnalyzer: ScreenAnalyzer
    @Volatile private var autoGatheringActive = false

    private val scanInterval = 4000L
    private val minimumConfidence = 60
    private val maximumDisplayedTargets = 20

    private val rssPriority = listOf("Emerging", "Gold", "Ore", "Wood", "Food", "Stone", "Other")

    private data class RssCandidate(
        val type: String,
        val level: Int,
        val x: Int,
        val y: Int,
        val confidence: Int,
        val occupied: Boolean,
        val flagScore: Int,
        val targetScore: Int,
        val badgeWidth: Int,
        val badgeHeight: Int,
        val moving: Boolean = false,
        val movingScore: Int = 0
    )

    private data class OcrDigit(val digit: Int,val centerX: Int,val centerY: Int,val width: Int,val height: Int,val rawText: String)
    private data class BlueComponent(var minX:Int,var minY:Int,var maxX:Int,var maxY:Int,var pixels:Int) {
        val centerX:Int get()=(minX+maxX)/2
        val centerY:Int get()=(minY+maxY)/2
        val width:Int get()=maxX-minX+1
        val height:Int get()=maxY-minY+1
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceAlive = true
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            gatheringEngine = AutoGatheringEngine(this, handler)
            screenAnalyzer = ScreenAnalyzer()
            handler.post { if (serviceAlive) showFloatingControl() }
        } catch (_: Exception) {
            safeStatus("Service ready\nOverlay retrying...")
            handler.postDelayed({ if (serviceAlive) recreateOverlay() }, 1000)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { stopAutomation() }

    private fun showFloatingControl() {
        if (!serviceAlive || overlayView != null) return
        val wm = windowManager ?: try { getSystemService(WINDOW_SERVICE) as WindowManager } catch (_: Exception) { return }
        windowManager = wm
        val container = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(10,8,10,8); setBackgroundColor(Color.rgb(65,65,65)) }
        val title = TextView(this).apply { text="Lords Assistant V22"; textSize=16f; setTextColor(Color.WHITE); gravity=Gravity.CENTER; setPadding(8,4,8,8) }
        title.setOnTouchListener(object: View.OnTouchListener {
            private var startX=0f; private var startY=0f; private var startParamX=0; private var startParamY=0
            override fun onTouch(view:View?,event:MotionEvent):Boolean {
                val params=overlayParams ?: return false
                when(event.actionMasked){
                    MotionEvent.ACTION_DOWN->{startX=event.rawX;startY=event.rawY;startParamX=params.x;startParamY=params.y;return true}
                    MotionEvent.ACTION_MOVE->{params.x=(startParamX+event.rawX-startX).toInt();params.y=(startParamY+event.rawY-startY).toInt();try{wm.updateViewLayout(container,params)}catch(_:Exception){};return true}
                    else->return true
                }
            }
        })

        val startButton=Button(this).apply{ text="▶ SCAN";setOnClickListener{try{if(running)stopAutomation()else startAutomation()}catch(e:Exception){safeStatus("Button error: ${e.javaClass.simpleName}")}} }
        val scanBtn=Button(this).apply{ text="🔍 ONE SCAN";setOnClickListener{try{scanScreen()}catch(e:Exception){safeStatus("Scan error: ${e.javaClass.simpleName}")}} }
        val gatherBtn=Button(this).apply{ text="⚔ AUTO GATHER";setOnClickListener{try{if(autoGatheringActive)stopAutoGathering()else startAutoGathering()}catch(e:Exception){safeStatus("Gather error: ${e.javaClass.simpleName}")}} }
        val info=TextView(this).apply{
            text="V22 Scanner ready\nDetects RSS levels 1-5\nOccupied/moving rejection enabled\nAUTO GATHER: SAFE VALIDATION ONLY"
            textSize=10.5f;setTextColor(Color.WHITE);gravity=Gravity.LEFT;setPadding(6,5,6,2);setLineSpacing(0f,1.05f)
        }
        infoText=info;startStopButton=startButton;scanButton=scanBtn;gatherButton=gatherBtn
        container.addView(title);container.addView(startButton);container.addView(scanBtn);container.addView(gatherBtn)
        val scroll=ScrollView(this).apply{isFillViewport=false;setPadding(0,2,0,0)}
        scroll.addView(info,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT))
        container.addView(scroll,LinearLayout.LayoutParams(470,520))
        val params=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT)
        params.gravity=Gravity.TOP or Gravity.START;params.x=100;params.y=80;overlayParams=params
        try{wm.addView(container,params);overlayView=container;safeStatus("V22 Scanner ready\nPress SCAN or ONE SCAN\nLevels 1-5\nOccupied/moving rejection enabled\nAUTO GATHER: SAFE VALIDATION ONLY")}catch(_:Exception){overlayView=null;overlayParams=null;infoText=null;startStopButton=null;scanButton=null;gatherButton=null;handler.postDelayed({if(serviceAlive)recreateOverlay()},1000)}
    }

    private fun recreateOverlay(){if(!serviceAlive||overlayView!=null)return;try{showFloatingControl()}catch(_:Exception){}}

    private fun startAutomation(){
        if(running||!serviceAlive)return
        running=true;updateStartStopButton();safeStatus("V22 AUTO SCAN started\nScanning every 4 seconds\nMovement/occupation rejection active\nStatus: ACTIVE")
        handler.removeCallbacks(scanRunnable);handler.postDelayed(scanRunnable,700)
    }

    private fun stopAutomation(){
        running=false;handler.removeCallbacks(scanRunnable);updateStartStopButton()
        safeStatus("SCAN STOPPED\nScanning paused\nAuto-gathering: ${if(autoGatheringActive)"ACTIVE" else "IDLE"}\nReady for input")
    }

    private fun startAutoGathering(){
        if(autoGatheringActive||!serviceAlive)return
        autoGatheringActive=true;updateGatherButton();gatheringEngine.startGathering()
        safeStatus("AUTO GATHER VALIDATION STARTED\nNo blind tapping\nOpen/verify/action phase is still locked\nStatus: SAFE VALIDATION")
    }

    private fun stopAutoGathering(){
        autoGatheringActive=false;gatheringEngine.stopGathering();updateGatherButton()
        safeStatus("AUTO GATHER VALIDATION STOPPED\nNo action was sent\nStatus: IDLE")
    }

    private val scanRunnable=object:Runnable{override fun run(){if(!running||!serviceAlive)return;try{scanScreen()}catch(e:Exception){safeStatus("Scan exception: ${e.javaClass.simpleName}")};if(running&&serviceAlive)handler.postDelayed(this,scanInterval)}}

    private fun scanScreen(){
        if(!serviceAlive)return
        if(Build.VERSION.SDK_INT<Build.VERSION_CODES.R){safeStatus("Android version does not support\nAccessibility screenshot API");return}
        if(screenshotInProgress){safeStatus("Scan already running...");return}
        screenshotInProgress=true;scanCount++;val thisScan=scanCount;safeStatus("Scan #$thisScan\nCapturing screen...")
        try{takeScreenshot(android.view.Display.DEFAULT_DISPLAY,mainExecutor,object:TakeScreenshotCallback{
            override fun onSuccess(screenshot:ScreenshotResult){if(!serviceAlive){screenshot.hardwareBuffer.close();screenshotInProgress=false;return};processScreenshot(screenshot,thisScan)}
            override fun onFailure(errorCode:Int){screenshotInProgress=false;safeStatus("Scan #$thisScan\nCapture failed: $errorCode\nStatus: Error")}
        })}catch(e:Exception){screenshotInProgress=false;safeStatus("Scan #$thisScan\nCapture exception: ${e.javaClass.simpleName}\nStatus: Error")}
    }

    private fun processScreenshot(screenshot:ScreenshotResult,thisScan:Int){
        try{
            val hardwareBitmap=try{Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer,screenshot.colorSpace)}catch(_:Exception){null}
            if(hardwareBitmap==null){safeStatus("Scan #$thisScan\nBitmap conversion failed");screenshotInProgress=false;return}
            val bitmap=try{hardwareBitmap.copy(Bitmap.Config.ARGB_8888,false)}catch(_:Exception){null}
            if(bitmap==null){safeStatus("Scan #$thisScan\nBitmap copy failed");screenshotInProgress=false;return}
            safeStatus("Scan #$thisScan\nAnalyzing screen...")
            analysisExecutor.execute{try{if(serviceAlive)analyseScreen(bitmap,thisScan)}finally{screenshotInProgress=false;try{bitmap.recycle()}catch(_:Exception){};try{hardwareBitmap.recycle()}catch(_:Exception){};try{screenshot.hardwareBuffer.close()}catch(_:Exception){}}}
        }catch(e:Exception){screenshotInProgress=false;safeStatus("Screenshot processing error: ${e.javaClass.simpleName}")}
    }

    private fun analyseScreen(bitmap:Bitmap,scanNumber:Int){
        try{
            val detections=screenAnalyzer.analyzeScreenshot(bitmap)
            if(detections.isEmpty()){safeStatus("Scan #$scanNumber\nNo safe RSS tiles found\nSearching...");return}
            val targets=detections.filter{it.confidence>=minimumConfidence}.map{detection->
                AutoGatheringEngine.RssTarget(
                    type=detection.type,level=detection.level,x=detection.centerX,y=detection.centerY,
                    confidence=detection.confidence,occupied=detection.occupied,
                    flagScore=if(detection.occupied)detection.movingScore else 0,
                    targetScore=calculateTargetScore(detection),moving=detection.moving,movingScore=detection.movingScore
                )
            }.sortedWith(compareBy<AutoGatheringEngine.RssTarget>{rssPriority.indexOf(it.type).let{v->if(v<0)99 else v}}.thenByDescending{it.level}.thenByDescending{it.confidence})
            if(targets.isEmpty()){safeStatus("Scan #$scanNumber\nFound ${detections.size} candidates\nConfidence too low\nThreshold: $minimumConfidence");return}
            val displayCount=minOf(targets.size,maximumDisplayedTargets)
            val resultText=StringBuilder().append("Scan #$scanNumber\n").append("Found $displayCount safe candidates:\n")
            for(i in 0 until displayCount){val target=targets[i];resultText.append("${i+1}. ${target.type} L${target.level} (${target.confidence}%)\n")}
            safeStatus(resultText.toString())
            if(autoGatheringActive&&targets.isNotEmpty()){
                val selectedTarget=gatheringEngine.processDetectedTargets(targets)
                if(selectedTarget!=null){handler.post{safeStatus("Scan #$scanNumber\nValidated target: ${selectedTarget.type} L${selectedTarget.level}\nNO TAP SENT\nConfidence: ${selectedTarget.confidence}%\nWaiting for open/verify phase")};gatheringEngine.performGatheringAction(selectedTarget)}
            }
        }catch(e:Exception){safeStatus("Analysis error: ${e.javaClass.simpleName}\n${e.message}")}
    }

    private fun calculateTargetScore(detection:ScreenAnalyzer.RssDetection):Int{
        var score=detection.confidence+detection.level*10
        if(detection.occupied)score-=30
        if(detection.moving)score-=40
        return score.coerceIn(0,100)
    }

    private fun updateStartStopButton(){handler.post{try{startStopButton?.text=if(running)"⏸ STOP" else "▶ SCAN"}catch(_:Exception){}}}
    private fun updateGatherButton(){handler.post{try{gatherButton?.text=if(autoGatheringActive)"⏸ STOP VALIDATION" else "⚔ AUTO GATHER"}catch(_:Exception){}}}
    private fun safeStatus(status:String){handler.post{try{infoText?.text=status}catch(_:Exception){}}}

    override fun onDestroy(){serviceAlive=false;running=false;autoGatheringActive=false;handler.removeCallbacksAndMessages(null);try{gatheringEngine.stopGathering()}catch(_:Exception){};try{analysisExecutor.shutdownNow()}catch(_:Exception){};try{textRecognizer.close()}catch(_:Exception){};try{overlayView?.let{windowManager?.removeView(it)}}catch(_:Exception){};overlayView=null;super.onDestroy()}
    private fun createPath(x:Float,y:Float):Path=Path().apply{moveTo(x,y);lineTo(x,y)}
}
