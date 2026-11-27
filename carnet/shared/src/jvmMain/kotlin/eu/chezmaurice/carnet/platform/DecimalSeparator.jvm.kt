package eu.chezmaurice.carnet.platform

actual fun decimalSeparator(): Char = java.text.DecimalFormatSymbols.getInstance().decimalSeparator
