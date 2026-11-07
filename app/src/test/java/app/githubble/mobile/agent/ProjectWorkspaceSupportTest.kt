package app.githubble.mobile.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectWorkspaceSupportTest {
    @Test fun detectsExplicitRussianProjectCreation() {
        assertEquals("IPTV чекер на Андроид",
            ProjectWorkspaceSupport.projectCreationName("создай проект условно IPTV чекер на Андроид"))
        assertEquals("MyProject", ProjectWorkspaceSupport.projectCreationName("создать проект MyProject"))
    }

    @Test fun detectsNaturalProductRequestWithoutProjectWord() {
        assertEquals(
            "MP3 плеер на Android",
            ProjectWorkspaceSupport.projectCreationName("Создай MP3 плеер на Android")
        )
        assertEquals(
            "сайт для моей мастерской",
            ProjectWorkspaceSupport.projectCreationName("Сделай сайт для моей мастерской")
        )
    }

    @Test fun ordinaryCodingRequestDoesNotCreateProject() {
        assertNull(ProjectWorkspaceSupport.projectCreationName("напиши Kotlin функцию"))
        assertNull(ProjectWorkspaceSupport.projectCreationName("исправь ошибку в этом коде"))
    }

    @Test fun visualAssetForExistingAppDoesNotCreateProject() {
        assertNull(ProjectWorkspaceSupport.projectCreationName("Создай иконку приложения OmniChat"))
        assertNull(ProjectWorkspaceSupport.projectCreationName("Сделай картинку для приложения"))
    }

    @Test fun detectsArchitectureApproval() {
        assertTrue(ProjectWorkspaceSupport.architectureSelectionIntent("Выбираю первый вариант"))
        assertTrue(ProjectWorkspaceSupport.architectureSelectionIntent("Берём вариант B"))
        assertTrue(ProjectWorkspaceSupport.architectureSelectionIntent("Останавливаюсь на втором"))
        assertFalse(ProjectWorkspaceSupport.architectureSelectionIntent("Какие есть варианты архитектуры?"))
    }

    @Test fun recognizesProjectType() {
        assertEquals("android", ProjectWorkspaceSupport.projectTypeHint("IPTV checker для Android"))
        assertEquals("web", ProjectWorkspaceSupport.projectTypeHint("сделай web проект"))
        assertEquals("ios", ProjectWorkspaceSupport.projectTypeHint("приложение для iOS"))
    }

    @Test fun planningStateHasRussianUserFacingName() {
        assertEquals("Планирование проекта", ProjectWorkspaceSupport.stateLabel(ProjectWorkspaceSupport.STATE_PLANNING))
    }
}

