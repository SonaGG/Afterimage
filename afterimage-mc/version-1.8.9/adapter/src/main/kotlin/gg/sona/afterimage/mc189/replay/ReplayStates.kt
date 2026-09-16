package gg.sona.afterimage.mc189.replay

import gg.sona.afterimage.mc189.state.ShadowClient
import gg.sona.afterimage.replay.session.ReplaySession

val ReplaySession.shadow: ShadowClient get() = state as ShadowClient
