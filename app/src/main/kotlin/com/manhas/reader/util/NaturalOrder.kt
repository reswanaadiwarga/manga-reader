package com.manhas.reader.util

object NaturalOrder : Comparator<String> {
  override fun compare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
      val ca = a[i]
      val cb = b[j]
      if (ca.isDigit() && cb.isDigit()) {
        var endA = i
        while (endA < a.length && a[endA].isDigit()) endA++
        var endB = j
        while (endB < b.length && b[endB].isDigit()) endB++
        var startA = i
        while (startA < endA - 1 && a[startA] == '0') startA++
        var startB = j
        while (startB < endB - 1 && b[startB] == '0') startB++
        val lengthA = endA - startA
        val lengthB = endB - startB
        if (lengthA != lengthB) return lengthA - lengthB
        for (k in 0 until lengthA) {
          val diff = a[startA + k].compareTo(b[startB + k])
          if (diff != 0) return diff
        }
        i = endA
        j = endB
      } else {
        val la = ca.lowercaseChar()
        val lb = cb.lowercaseChar()
        if (la != lb) return la.compareTo(lb)
        i++
        j++
      }
    }
    return (a.length - i) - (b.length - j)
  }
}
