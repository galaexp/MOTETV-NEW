package com.gala.motetv.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import polo.wire.protobuf.Status

class PinEntryUiStateMachineTest {

    class PinInputHarness {
        var pinText: String = ""
        var isSubmitting: Boolean = false
        var submitted: Boolean = false
        var dialogVisible: Boolean = true
        var errorMessage: String? = null
        val submittedPins = mutableListOf<String>()

        fun onNewErrorMessage(msg: String?) {
            errorMessage = msg
            if (msg != null) {
                submitted = false
                isSubmitting = false
                pinText = ""
            }
        }

        fun onValueChange(input: String) {
            if (!isSubmitting && !submitted) {
                val sanitized = input.filter { it.isLetterOrDigit() }.take(6).uppercase()
                pinText = sanitized

                if (sanitized.length == 6) {
                    trySubmit(sanitized)
                }
            }
        }

        fun onImeDone() {
            trySubmit(pinText)
        }

        fun onManualPairButtonClick() {
            trySubmit(pinText)
        }

        fun onCancelClick() {
            if (!isSubmitting) {
                dialogVisible = false
            }
        }

        fun onFocusLossOrKeyboardHide() {
            // Does nothing to dialog visibility
        }

        fun onSecretAck(status: Status) {
            if (status == Status.STATUS_OK) {
                dialogVisible = false
                isSubmitting = false
            } else if (status == Status.STATUS_BAD_SECRET) {
                onNewErrorMessage("Incorrect PIN. Please enter the 6-character code shown on your TV.")
            }
        }

        private fun trySubmit(rawPin: String) {
            if (!isSubmitting && !submitted && rawPin.length == 6) {
                submitted = true
                isSubmitting = true
                submittedPins.add(rawPin)
            }
        }
    }

    @Test
    fun testPartialInputKeepsDialogActiveWithoutSubmitting() {
        val harness = PinInputHarness()

        harness.onValueChange("A")
        assertEquals("A", harness.pinText)
        assertEquals(0, harness.submittedPins.size)
        assertTrue(harness.dialogVisible)
        assertFalse(harness.isSubmitting)

        harness.onValueChange("AB")
        assertEquals("AB", harness.pinText)
        assertEquals(0, harness.submittedPins.size)
        assertTrue(harness.dialogVisible)

        harness.onValueChange("ABCDE")
        assertEquals("ABCDE", harness.pinText)
        assertEquals(0, harness.submittedPins.size)
        assertTrue(harness.dialogVisible)
    }

    @Test
    fun testSixCharactersTriggersExactlyOneSubmission() {
        val harness = PinInputHarness()

        harness.onValueChange("ABCDEF")
        assertEquals("ABCDEF", harness.pinText)
        assertEquals(1, harness.submittedPins.size)
        assertEquals("ABCDEF", harness.submittedPins.first())
        assertTrue(harness.isSubmitting)
        assertTrue(harness.dialogVisible) // Dialog MUST remain visible while submitting!
    }

    @Test
    fun testSeventhCharacterIsIgnoredAndDoesNotDoubleSubmit() {
        val harness = PinInputHarness()

        harness.onValueChange("ABCDEF")
        assertEquals(1, harness.submittedPins.size)

        // Attempting to type a 7th character
        harness.onValueChange("ABCDEFG")
        assertEquals(1, harness.submittedPins.size)
        assertEquals("ABCDEF", harness.pinText)
    }

    @Test
    fun testImeDoneAndManualButtonDoNotDoubleSubmit() {
        val harness = PinInputHarness()

        harness.onValueChange("123456")
        assertEquals(1, harness.submittedPins.size)

        // Duplicate IME Done action
        harness.onImeDone()
        assertEquals(1, harness.submittedPins.size)

        // Duplicate button click
        harness.onManualPairButtonClick()
        assertEquals(1, harness.submittedPins.size)
    }

    @Test
    fun testFocusLossDoesNotDismissDialog() {
        val harness = PinInputHarness()
        harness.onValueChange("123")
        harness.onFocusLossOrKeyboardHide()

        assertTrue(harness.dialogVisible)
        assertEquals("123", harness.pinText)
    }

    @Test
    fun testBadSecretKeepsDialogVisibleAndAllowsRetry() {
        val harness = PinInputHarness()

        harness.onValueChange("112233")
        assertEquals(1, harness.submittedPins.size)
        assertTrue(harness.dialogVisible)

        // TV returns STATUS_BAD_SECRET
        harness.onSecretAck(Status.STATUS_BAD_SECRET)

        assertTrue(harness.dialogVisible)
        assertFalse(harness.isSubmitting)
        assertFalse(harness.submitted)
        assertEquals("", harness.pinText)
        assertTrue(harness.errorMessage?.contains("Incorrect PIN") == true)

        // User enters the correct PIN on retry
        harness.onValueChange("445566")
        assertEquals(2, harness.submittedPins.size)
        assertEquals("445566", harness.submittedPins[1])
        assertTrue(harness.dialogVisible)

        // TV returns STATUS_OK
        harness.onSecretAck(Status.STATUS_OK)
        assertFalse(harness.dialogVisible) // Now dismisses on success!
    }

    @Test
    fun testCancelButtonClosesDialog() {
        val harness = PinInputHarness()
        harness.onValueChange("12")
        harness.onCancelClick()
        assertFalse(harness.dialogVisible)
    }
}
