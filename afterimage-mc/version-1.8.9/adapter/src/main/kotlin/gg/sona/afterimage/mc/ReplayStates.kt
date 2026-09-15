package gg.sona.afterimage.mc

import gg.sona.afterimage.protocol47.shadow.ShadowClient
import gg.sona.afterimage.replay.session.ReplaySession

val ReplaySession.shadow: ShadowClient get() = state as ShadowClient
