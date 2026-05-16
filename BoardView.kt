package com.xiangqiai.engine

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

class BoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ============ 棋盘状态 ============
    // board[r][c]: 0=空, 正数=红方, 负数=黑方
    // 1/-1=King, 2/-2=Advisor, 3/-3=Bishop, 4/-4=Knight, 5/-5=Rook, 6/-6=Cannon, 7/-7=Pawn
    private val board = Array(10) { IntArray(9) }
    private var sideToMove = 1  // 1=红, -1=黑
    var humanColor: Int = 1     // 人类执子颜色：1=红，-1=黑
    private var locked = false  // 锁定输入（AI 思考时）

    // 选中的格子
    private var selectedRow = -1
    private var selectedCol = -1
    private val legalTargets = mutableListOf<Pair<Int, Int>>()

    // 上一步走子（用于高亮）
    private var lastFromR = -1
    private var lastFromC = -1
    private var lastToR = -1
    private var lastToC = -1

    // 走子动画
    private var animPieceCode = 0
    private var animFromR = 0f
    private var animFromC = 0f
    private var animToR = 0f
    private var animToC = 0f
    private var animProgress = 0f
    private var animator: ValueAnimator? = null

    // 回调
    var onMoveListener: ((fromR: Int, fromC: Int, toR: Int, toC: Int) -> Unit)? = null
    var legalMoveProvider: ((fromR: Int, fromC: Int) -> List<Pair<Int, Int>>)? = null

    // ============ 绘制相关 ============
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }

    private var cellSize = 0f
    private var boardLeft = 0f
    private var boardTop = 0f
    private var boardRight = 0f
    private var boardBottom = 0f
    private var pieceRadius = 0f

    // 颜色
    private val colorBoardBg = Color.parseColor("#E8C078")
    private val colorBoardBgDark = Color.parseColor("#C99860")
    private val colorLine = Color.parseColor("#3A2810")
    private val colorRedPiece = Color.parseColor("#C82828")
    private val colorBlackPiece = Color.parseColor("#1A1A1A")
    private val colorPieceBg = Color.parseColor("#F4E1B5")
    private val colorPieceEdge = Color.parseColor("#8B5A2B")
    private val colorSelected = Color.parseColor("#FFD700")
    private val colorLegalDot = Color.parseColor("#4A90E2")
    private val colorLastMove = Color.parseColor("#FFA500")

    init {
        resetBoard()
    }

    // ============ 棋盘初始化 ============
    fun resetBoard() {
        for (r in 0..9) for (c in 0..8) board[r][c] = 0
        // 黑方在上（行 0-4）
        board[0][0] = -5; board[0][1] = -4; board[0][2] = -3; board[0][3] = -2
        board[0][4] = -1; board[0][5] = -2; board[0][6] = -3; board[0][7] = -4; board[0][8] = -5
        board[2][1] = -6; board[2][7] = -6
        board[3][0] = -7; board[3][2] = -7; board[3][4] = -7; board[3][6] = -7; board[3][8] = -7
        // 红方在下（行 5-9）
        board[6][0] = 7; board[6][2] = 7; board[6][4] = 7; board[6][6] = 7; board[6][8] = 7
        board[7][1] = 6; board[7][7] = 6
        board[9][0] = 5; board[9][1] = 4; board[9][2] = 3; board[9][3] = 2
        board[9][4] = 1; board[9][5] = 2; board[9][6] = 3; board[9][7] = 4; board[9][8] = 5

        sideToMove = 1
        selectedRow = -1; selectedCol = -1
        legalTargets.clear()
        lastFromR = -1; lastFromC = -1; lastToR = -1; lastToC = -1
        locked = false
        invalidate()
    }

    fun setLocked(lock: Boolean) {
        locked = lock
    }

    fun setSideToMove(side: Int) {
        sideToMove = side
    }

    fun getSideToMove(): Int = sideToMove

    /**
     * 由引擎驱动的走子（不触发回调，直接落子并播放动画）
     */
    fun applyMove(fromR: Int, fromC: Int, toR: Int, toC: Int) {
        if (fromR !in 0..9 || fromC !in 0..8 || toR !in 0..9 || toC !in 0..8) return
        val piece = board[fromR][fromC]
        if (piece == 0) return

        animPieceCode = piece
        animFromR = fromR.toFloat()
        animFromC = fromC.toFloat()
        animToR = toR.toFloat()
        animToC = toC.toFloat()
        animProgress = 0f

        // 先把起点棋子清空（动画期间在浮层绘制）
        board[fromR][fromC] = 0

        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                animProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    board[toR][toC] = animPieceCode
                    animPieceCode = 0
                    lastFromR = fromR; lastFromC = fromC
                    lastToR = toR; lastToC = toC
                    sideToMove = -sideToMove
                    selectedRow = -1; selectedCol = -1
                    legalTargets.clear()
                    invalidate()
                }
            })
            start()
        }
    }

    /**
     * 将当前局面导出为 FEN（用于喂给引擎）
     */
    fun toFen(): String {
        val sb = StringBuilder()
        for (r in 0..9) {
            var empty = 0
            for (c in 0..8) {
                val p = board[r][c]
                if (p == 0) {
                    empty++
                } else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    sb.append(pieceToChar(p))
                }
            }
            if (empty > 0) sb.append(empty)
            if (r < 9) sb.append('/')
        }
        sb.append(' ').append(if (sideToMove == 1) 'w' else 'b')
        sb.append(" - - 0 1")
        return sb.toString()
    }

    private fun pieceToChar(p: Int): Char {
        return when (p) {
            1 -> 'K'; 2 -> 'A'; 3 -> 'B'; 4 -> 'N'; 5 -> 'R'; 6 -> 'C'; 7 -> 'P'
            -1 -> 'k'; -2 -> 'a'; -3 -> 'b'; -4 -> 'n'; -5 -> 'r'; -6 -> 'c'; -7 -> 'p'
            else -> '.'
        }
    }

    // ============ 尺寸 ============
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        // 棋盘 9 列 10 行，比例 9:10
        val h = (w * 10.0 / 9.0).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 留出边距，棋盘内部 9x10 网格（8 列间隔，9 行间隔）
        val padding = w * 0.05f
        val gridW = w - padding * 2
        cellSize = gridW / 8f
        val gridH = cellSize * 9f
        boardLeft = padding
        boardTop = (h - gridH) / 2f
        boardRight = w - padding
        boardBottom = boardTop + gridH
        pieceRadius = cellSize * 0.42f
    }

    // ============ 绘制 ============
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawBoardBackground(canvas)
        drawGridLines(canvas)
        drawPalaceDiagonals(canvas)
        drawRiverText(canvas)
        drawLastMoveHighlight(canvas)
        drawSelectionAndLegal(canvas)
        drawPieces(canvas)
        drawAnimatingPiece(canvas)
    }

    private fun drawBoardBackground(canvas: Canvas) {
        // 渐变木色背景
        val gradient = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            colorBoardBg, colorBoardBgDark, Shader.TileMode.CLAMP
        )
        paint.shader = gradient
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
    }

    private fun drawGridLines(canvas: Canvas) {
        paint.color = colorLine
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f

        // 横线 10 条
        for (r in 0..9) {
            val y = boardTop + r * cellSize
            canvas.drawLine(boardLeft, y, boardRight, y, paint)
        }
        // 竖线 9 条，但中间 7 条在河界处断开
        for (c in 0..8) {
            val x = boardLeft + c * cellSize
            if (c == 0 || c == 8) {
                canvas.drawLine(x, boardTop, x, boardBottom, paint)
            } else {
                // 上半段
                canvas.drawLine(x, boardTop, x, boardTop + 4 * cellSize, paint)
                // 下半段
                canvas.drawLine(x, boardTop + 5 * cellSize, x, boardBottom, paint)
            }
        }
        // 外框加粗
        paint.strokeWidth = 4f
        canvas.drawRect(boardLeft, boardTop, boardRight, boardBottom, paint)
        paint.strokeWidth = 2.5f
    }

    private fun drawPalaceDiagonals(canvas: Canvas) {
        paint.color = colorLine
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f

        // 黑方九宫（0,3) - (2,5)
        val blackTopLeft = pointOf(0, 3)
        val blackTopRight = pointOf(0, 5)
        val blackBotLeft = pointOf(2, 3)
        val blackBotRight = pointOf(2, 5)
        canvas.drawLine(blackTopLeft.x, blackTopLeft.y, blackBotRight.x, blackBotRight.y, paint)
        canvas.drawLine(blackTopRight.x, blackTopRight.y, blackBotLeft.x, blackBotLeft.y, paint)

        // 红方九宫（7,3) - (9,5)
        val redTopLeft = pointOf(7, 3)
        val redTopRight = pointOf(7, 5)
        val redBotLeft = pointOf(9, 3)
        val redBotRight = pointOf(9, 5)
        canvas.drawLine(redTopLeft.x, redTopLeft.y, redBotRight.x, redBotRight.y, paint)
        canvas.drawLine(redTopRight.x, redTopRight.y, redBotLeft.x, redBotLeft.y, paint)
    }

    private fun drawRiverText(canvas: Canvas) {
        textPaint.color = colorLine
        textPaint.textSize = cellSize * 0.5f
        val cy = boardTop + 4.5f * cellSize + textPaint.textSize / 3f
        canvas.drawText("楚 河", boardLeft + cellSize * 1.5f, cy, textPaint)
        canvas.drawText("汉 界", boardLeft + cellSize * 6.5f, cy, textPaint)
    }

    private fun drawLastMoveHighlight(canvas: Canvas) {
        if (lastFromR < 0) return
        paint.style = Paint.Style.FILL
        paint.color = colorLastMove
        paint.alpha = 80
        val pFrom = pointOf(lastFromR, lastFromC)
        val pTo = pointOf(lastToR, lastToC)
        canvas.drawCircle(pFrom.x, pFrom.y, pieceRadius * 1.1f, paint)
        canvas.drawCircle(pTo.x, pTo.y, pieceRadius * 1.1f, paint)
        paint.alpha = 255
    }

    private fun drawSelectionAndLegal(canvas: Canvas) {
        if (selectedRow >= 0) {
            paint.style = Paint.Style.STROKE
            paint.color = colorSelected
            paint.strokeWidth = 5f
            val p = pointOf(selectedRow, selectedCol)
            canvas.drawCircle(p.x, p.y, pieceRadius * 1.1f, paint)
        }
        if (legalTargets.isNotEmpty()) {
            paint.style = Paint.Style.FILL
            paint.color = colorLegalDot
            paint.alpha = 180
            for ((r, c) in legalTargets) {
                val p = pointOf(r, c)
                if (board[r][c] != 0) {
                    // 吃子点：画圆环
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 4f
                    canvas.drawCircle(p.x, p.y, pieceRadius * 1.05f, paint)
                    paint.style = Paint.Style.FILL
                } else {
                    canvas.drawCircle(p.x, p.y, cellSize * 0.13f, paint)
                }
            }
            paint.alpha = 255
        }
    }

    private fun drawPieces(canvas: Canvas) {
        for (r in 0..9) {
            for (c in 0..8) {
                val p = board[r][c]
                if (p != 0) {
                    val pt = pointOf(r, c)
                    drawPieceAt(canvas, p, pt.x, pt.y)
                }
            }
        }
    }

    private fun drawAnimatingPiece(canvas: Canvas) {
        if (animPieceCode == 0) return
        val rr = animFromR + (animToR - animFromR) * animProgress
        val cc = animFromC + (animToC - animFromC) * animProgress
        val x = boardLeft + cc * cellSize
        val y = boardTop + rr * cellSize
        drawPieceAt(canvas, animPieceCode, x, y)
    }

    private fun drawPieceAt(canvas: Canvas, piece: Int, cx: Float, cy: Float) {
        // 棋子底色
        paint.style = Paint.Style.FILL
        paint.color = colorPieceBg
        canvas.drawCircle(cx, cy, pieceRadius, paint)
        // 边框
        paint.style = Paint.Style.STROKE
        paint.color = colorPieceEdge
        paint.strokeWidth = 3f
        canvas.drawCircle(cx, cy, pieceRadius, paint)
        // 内圈装饰
        paint.strokeWidth = 1.5f
        canvas.drawCircle(cx, cy, pieceRadius * 0.85f, paint)
        // 文字
        val isRed = piece > 0
        textPaint.color = if (isRed) colorRedPiece else colorBlackPiece
        textPaint.textSize = pieceRadius * 1.2f
        val ch = pieceCharacter(piece)
        val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(ch, cx, baseline, textPaint)
    }

    private fun pieceCharacter(p: Int): String = when (p) {
        1 -> "帥"; 2 -> "仕"; 3 -> "相"; 4 -> "馬"; 5 -> "車"; 6 -> "炮"; 7 -> "兵"
        -1 -> "將"; -2 -> "士"; -3 -> "象"; -4 -> "馬"; -5 -> "車"; -6 -> "砲"; -7 -> "卒"
        else -> ""
    }

    private fun pointOf(r: Int, c: Int): PointF {
        return PointF(boardLeft + c * cellSize, boardTop + r * cellSize)
    }

    // ============ 触摸交互 ============
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (locked) return true
        if (sideToMove != humanColor) return true
        if (event.action != MotionEvent.ACTION_DOWN) return true

        val c = ((event.x - boardLeft) / cellSize + 0.5f).toInt()
        val r = ((event.y - boardTop) / cellSize + 0.5f).toInt()
        if (r !in 0..9 || c !in 0..8) return true

        if (selectedRow == -1) {
            // 第一次点击：选子
            val p = board[r][c]
            if (p != 0 && (p > 0) == (humanColor > 0)) {
                selectedRow = r; selectedCol = c
                legalTargets.clear()
                legalMoveProvider?.invoke(r, c)?.let { legalTargets.addAll(it) }
                invalidate()
            }
        } else {
            // 第二次点击
            if (r == selectedRow && c == selectedCol) {
                // 取消选中
                selectedRow = -1; selectedCol = -1
                legalTargets.clear()
                invalidate()
                return true
            }
            val target = board[r][c]
            if (target != 0 && (target > 0) == (humanColor > 0)) {
                // 换选另一个己方棋子
                selectedRow = r; selectedCol = c
                legalTargets.clear()
                legalMoveProvider?.invoke(r, c)?.let { legalTargets.addAll(it) }
                invalidate()
                return true
            }
            // 尝试走子
            if (legalTargets.any { it.first == r && it.second == c }) {
                val fr = selectedRow; val fc = selectedCol
                applyMove(fr, fc, r, c)
                onMoveListener?.invoke(fr, fc, r, c)
            } else {
                // 非法目标，取消选择
                selectedRow = -1; selectedCol = -1
                legalTargets.clear()
                invalidate()
            }
        }
        return true
    }
}
