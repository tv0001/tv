package com.tv.player.ui

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * What the picture's controller talks to. Every episode is loaded on its own, so for the player there is only ever one item
 * and its "next" and "previous" buttons are dead (previous would restart the picture). Here they mean the next and the
 * previous episode, and are greyed out only where there is none.
 */
@UnstableApi
class EpisodePlayer(player: Player, private val canStep: (Int) -> Boolean, private val step: (Int) -> Unit) : ForwardingPlayer(player) {
    override fun getAvailableCommands(): Player.Commands {
        val commands = super.getAvailableCommands().buildUpon()
        for (c in listOf(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)) if (canStep(1)) commands.add(c) else commands.remove(c)
        for (c in listOf(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)) if (canStep(-1)) commands.add(c) else commands.remove(c)
        return commands.build()
    }

    // ForwardingPlayer answers this from the wrapped player, not from getAvailableCommands above.
    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    override fun hasNextMediaItem(): Boolean = canStep(1)

    override fun hasPreviousMediaItem(): Boolean = canStep(-1)

    override fun seekToNext() = step(1)

    override fun seekToNextMediaItem() = step(1)

    override fun seekToPrevious() = step(-1)

    override fun seekToPreviousMediaItem() = step(-1)
}
