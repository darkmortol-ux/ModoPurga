package com.darkmortol.modopurga.game;

/** Estados posibles de la partida. */
public enum GameState {
    WAITING, // esperando que el host use /purga start
    LOOTING, // fase safe: se busca loot, PvP apagado
    PVP,     // arena reducida: cuenta regresiva y luego combate
    ENDING   // hay ganador (o tiempo agotado); se espera antes de resetear
}
