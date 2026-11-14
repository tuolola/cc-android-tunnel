// Copyright 2026 CC Android Tunnel contributors.
// Native Android rewrite/adaptation of CC Desk Tunnel, modified in 2026.
// Apache-2.0; upstream attribution and third-party exceptions are listed in NOTICE.

package dev.ccdesk.nativeapp.ui

import android.content.Context
import android.graphics.Paint
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.LineHeightSpan
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import androidx.appcompat.widget.AppCompatEditText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.ceil
import kotlin.math.floor

/** System editor preserves Android selection handles, IME composition and the native context menu. */
@Composable internal fun NativeComposer(value:String,onChange:(String)->Unit,placeholder:String,enabled:Boolean,modifier:Modifier=Modifier) {
    val colors=LocalDeskColors.current
    val context=LocalContext.current
    val configuration=LocalConfiguration.current
    // AndroidView survives uiMode changes. A TextView retains its constructor's ColorStateList,
    // so resolve the current AppCompat default without replacing the live editor/Editable.
    val hintColors=remember(context,configuration.uiMode){AppCompatEditText(context).hintTextColors}
    val change by rememberUpdatedState(onChange)
    val editor=remember{arrayOfNulls<ComposerEditText>(1)}
    var hasFocus by remember{mutableStateOf(false)}
    AndroidView(modifier=modifier.semantics {
        contentDescription=placeholder
        editableText=AnnotatedString(value)
        isEditable=enabled
        focused=hasFocus
        if(!enabled)disabled()
        requestFocus { editor[0]?.requestFocus() ?: false }
        setText { if(enabled){editor[0]?.let{view->view.setText(it.text);view.setSelection(view.text?.length ?: 0)};true}else false }
        insertTextAtCursor { if(enabled){editor[0]?.let{view->val a=view.selectionStart.coerceAtLeast(0);val b=view.selectionEnd.coerceAtLeast(0);view.text?.replace(minOf(a,b),maxOf(a,b),it.text)};true}else false }
    },factory={ context->ComposerEditText(context).also { view->
        editor[0]=view
        view.setOnFocusChangeListener{_,focused->hasFocus=focused}
        view.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){}
            override fun afterTextChanged(s:Editable?){view.applyLineMetrics();change(s?.toString().orEmpty())}
        })
    }},update={view->
        view.contentDescription=placeholder;view.hint=placeholder
        view.setTextColor(colors.foreground.toArgb())
        if(view.hintTextColors!==hintColors)view.setHintTextColor(hintColors)
        view.isEnabled=enabled
        if(view.text.toString()!=value){view.setText(value);view.setSelection(view.text?.length ?: 0)}
    })
}

private class ComposerEditText(context:Context):AppCompatEditText(context) {
    private val scale=resources.displayMetrics.density
    private val line=ceil(24*resources.displayMetrics.scaledDensity).toInt()
    private val lineSpan=object:LineHeightSpan {
        override fun chooseHeight(text:CharSequence,start:Int,end:Int,spanstartv:Int,v:Int,fm:Paint.FontMetricsInt){
            val leading=line-(-fm.ascent+fm.descent)
            fm.ascent-=ceil(leading/2.0).toInt();fm.descent+=floor(leading/2.0).toInt()
            if(start==0)fm.top=fm.ascent
            if(end==text.length)fm.bottom=fm.descent
        }
    }
    init {
        background=null;setPadding((7*scale).toInt(),0,(7*scale).toInt(),0)
        textSize=16f;includeFontPadding=true;gravity=Gravity.TOP or Gravity.START
        inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions=EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        filters=arrayOf(InputFilter.LengthFilter(16000));minHeight=0;minimumHeight=0
        setHorizontallyScrolling(false);isVerticalScrollBarEnabled=false
        setSelectAllOnFocus(false)
    }
    fun applyLineMetrics(){text?.let{it.setSpan(lineSpan,0,it.length,Spanned.SPAN_INCLUSIVE_INCLUSIVE)};requestLayout()}
    override fun onTextContextMenuItem(id:Int)=super.onTextContextMenuItem(if(id==android.R.id.paste)android.R.id.pasteAsPlainText else id)
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
        // Natural text layout grows to six lines, then the editor itself scrolls the caret into view.
        val maximum=if(MeasureSpec.getMode(heightMeasureSpec)==MeasureSpec.UNSPECIFIED)(144*scale).toInt() else minOf((144*scale).toInt(),MeasureSpec.getSize(heightMeasureSpec))
        super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec(maximum,MeasureSpec.AT_MOST))
        val desired=(if(text.isNullOrEmpty())26*scale else measuredHeight.toFloat()).toInt().coerceIn(minOf((26*scale).toInt(),maximum),maximum)
        super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec(desired,MeasureSpec.EXACTLY))
    }
}
