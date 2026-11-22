package com.dan.steuerhaus.ui;

import java.util.Set;

/**
 * Eine benannte Anordnung der Docks (Werkbuch HalligHooge 110, 5.1): welche Docks offen sind und welche Abschnitte
 * darin aufgeklappt (null: wie sie stehen). Die App meldet ihre Arbeitsbereiche beim Hauptfenster an.
 */
public record Arbeitsbereich(String kennung, String name, boolean links, boolean rechts, boolean unten, Set<String> abschnitteOffen) { }
