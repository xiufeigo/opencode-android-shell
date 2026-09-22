package com.xiufeigo.opencodeshell.ui.home

import com.xiufeigo.opencodeshell.data.HomeProjectRecords

// WAVE 2.3b P1 (A2 #7) - newSessionProject() (home/model.ts:24-29):
//
//   newSessionProject() = selectedProject() ?? projects.last()
//
// The directory a + outside a session drafts in: the selected project wins,
// else the MRU marker (touch -> lastProject), else the first opened project.
// Pure so App.kt's onNewSession branch and the draft route resolve the same
// directory without a device; the composer-model inheritance on a session route
// stays in App.kt (it reads ChatViewModel.selectedModel, which this layer
// cannot see - see SessionTabs.draftTarget for the branch decision).
object NewSessionProject {

    // @param selectedDirectory layout.home.selection()'s directory, if any.
    // @param opened the client-local opened-project record.
    fun resolve(
        selectedDirectory: String?,
        opened: HomeProjectRecords.State,
    ): String? {
        val selected = selectedDirectory?.takeIf { it.isNotBlank() }
        if (selected != null) return selected
        val recent = opened.lastProject?.takeIf { it.isNotBlank() }
        if (recent != null) return recent
        return opened.list.firstOrNull()?.worktree?.takeIf { it.isNotBlank() }
    }
}
