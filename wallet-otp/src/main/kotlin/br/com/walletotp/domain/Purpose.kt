package br.com.walletotp.domain

/** What a code is for (ADR-001, decision 2): a code of one purpose never verifies another. */
enum class Purpose { SIGNUP, LOGIN, PAYMENT_APPROVAL }

/** Where the code goes. Only email for now; SMS would be another value and another sender adapter. */
enum class Channel { EMAIL }
