package com.lyricstage.core.stage

import com.lyricstage.core.stage.templates.Flow
import com.lyricstage.core.stage.templates.Graph
import com.lyricstage.core.stage.templates.Letters
import com.lyricstage.core.stage.templates.Scatter
import com.lyricstage.core.stage.templates.Slide
import com.lyricstage.core.stage.templates.Stack
import com.lyricstage.core.stage.templates.Zoom

object Templates {
    val all: List<LyricTemplate> = listOf(Stack, Slide, Zoom, Letters, Scatter, Graph, Flow)

    fun of(id: TemplateId): LyricTemplate = when (id) {
        TemplateId.STACK -> Stack
        TemplateId.SLIDE -> Slide
        TemplateId.ZOOM -> Zoom
        TemplateId.LETTERS -> Letters
        TemplateId.SCATTER -> Scatter
        TemplateId.GRAPH -> Graph
        TemplateId.FLOW -> Flow
    }
}
