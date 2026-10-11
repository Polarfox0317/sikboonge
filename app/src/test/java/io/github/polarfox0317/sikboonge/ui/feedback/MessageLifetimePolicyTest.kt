package io.github.polarfox0317.sikboonge.ui.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MessageLifetimePolicyTest {
    @Test
    fun preparationHandedToQueuedOrRunningDownloadKeepsItsReadingTime() {
        val policy = MessageLifetimePolicy()
        policy.updateProgress(jobActive = true, text = "저장 준비 중")
        val preparation = policy.current!!

        // A Library download now owns the transfer; preparation and conversion are inactive.
        policy.updateProgress(jobActive = false, text = "대기 중")
        assertSame(preparation, policy.current)
        policy.updateProgress(jobActive = false, text = "다운로드 중")
        assertSame(preparation, policy.current)

        policy.dismiss(preparation.id)
        assertNull(policy.current)
    }

    @Test
    fun continuousWorkerProgressDoesNotReplaceOrRestartThePreparationMessage() {
        val policy = MessageLifetimePolicy()
        policy.updateProgress(jobActive = true, text = "저장 준비 중")
        val preparation = policy.current

        policy.updateProgress(jobActive = true, text = "다운로드 중 1%")
        policy.updateProgress(jobActive = true, text = "다운로드 중 75%")
        assertSame(preparation, policy.current)
    }

    @Test
    fun inactiveJobRearmsNextProgressWithoutDismissingCurrentMessage() {
        val policy = MessageLifetimePolicy()
        policy.updateProgress(jobActive = true, text = "저장 준비 중")
        val preparation = policy.current
        policy.updateProgress(jobActive = false, text = "")
        assertSame(preparation, policy.current)

        policy.updateProgress(jobActive = true, text = "오디오 추출 중")
        assertEquals("오디오 추출 중", policy.current?.text)
        assertNotEquals(preparation?.id, policy.current?.id)
    }

    @Test
    fun repeatedOrBlankNoticeKeepsCurrentMessageAndTimerGeneration() {
        val policy = MessageLifetimePolicy()
        policy.show("저장을 시작했어요.")
        val notice = policy.current
        policy.show("저장을 시작했어요.")
        policy.show("")
        policy.show(" \n\t")
        assertSame(notice, policy.current)
    }

    @Test
    fun newerNoticeReplacesCurrentButOldTimerCannotDismissIt() {
        val policy = MessageLifetimePolicy()
        policy.show("저장을 시작했어요.")
        val oldId = policy.current!!.id
        policy.show("저장할 공간이 부족해요.")
        val replacement = policy.current!!
        policy.dismiss(oldId)
        assertSame(replacement, policy.current)
        policy.dismiss(replacement.id)
        assertNull(policy.current)
    }

    @Test
    fun sameTextAfterExpiryStartsANewGeneration() {
        val policy = MessageLifetimePolicy()
        policy.show("저장을 시작했어요.")
        val expiredId = policy.current!!.id
        policy.dismiss(expiredId)
        policy.show("저장을 시작했어요.")
        assertNotEquals(expiredId, policy.current!!.id)
        policy.dismiss(expiredId)
        assertEquals("저장을 시작했어요.", policy.current?.text)
    }

    @Test
    fun persistentMessageCanStillBeExplicitlyClearedOrReplaced() {
        val policy = MessageLifetimePolicy()
        policy.show("재생 오류", persistent = true)
        policy.dismiss()
        assertNull(policy.current)
        policy.show("재생 오류", persistent = true)
        policy.show("다른 안내")
        assertEquals(false, policy.current?.persistent)
        assertEquals("다른 안내", policy.current?.text)
    }
}
