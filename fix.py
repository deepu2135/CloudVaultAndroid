import sys
content = open('app/src/main/java/com/cloudvault/app/AutoBackupManager.kt').read()

import re

# Add imports
if 'import kotlinx.coroutines.async' not in content:
    content = content.replace('import kotlinx.coroutines.awaitAll', 'import kotlinx.coroutines.awaitAll\nimport kotlinx.coroutines.async\nimport kotlinx.coroutines.sync.withPermit\nimport kotlinx.coroutines.coroutineScope')

old_code = """
            var successCount = 0
            _backupStatus.value = "Backing up $total new item(s)..."

            val semaphore = kotlinx.coroutines.sync.Semaphore(3)
            val successCountAtomic = java.util.concurrent.atomic.AtomicInteger(0)
            val completedCountAtomic = java.util.concurrent.atomic.AtomicInteger(0)
            
            kotlinx.coroutines.coroutineScope {
                unbackedFiles.map { file ->
                    kotlinx.coroutines.async {
                        semaphore.withPermit {
"""

new_code = """
            var successCount = 0
            _backupStatus.value = "Backing up $total new item(s)..."

            val semaphore = kotlinx.coroutines.sync.Semaphore(3)
            val successCountAtomic = java.util.concurrent.atomic.AtomicInteger(0)
            val completedCountAtomic = java.util.concurrent.atomic.AtomicInteger(0)
            
            coroutineScope {
                unbackedFiles.map { file ->
                    async {
                        semaphore.withPermit {
"""
content = content.replace(old_code.strip(), new_code.strip())

old_code2 = """
                }.awaitAll()
            }
            
            var successCount = successCountAtomic.get()
"""
new_code2 = """
                }.awaitAll()
            }
            
            successCount = successCountAtomic.get()
"""
content = content.replace(old_code2.strip(), new_code2.strip())

# Fix line 207: 'if' must have both main and 'else' branches if used as an expression
# Wait, let's see what is on line 207:
# 207:                                     if (success) {
# 208:                                         AutoBackupPreferences.markSignatureBackedUp(context, file.signature)
# 209:                                         successCountAtomic.incrementAndGet()
# 210:                                     }
# Why is this an expression? 
# Ah! In Kotlin, if it's the last expression in `withPermit`, and `withPermit` returns a value, the `if` without `else` evaluates to Unit, which is fine!
# But wait, `async` returns a value. If the last thing in `async` is a try/finally, it evaluates to the value of try!
# And the try ends with `if (success) { ... }` which is NOT an expression if it has no else! 
# So I should put `Unit` at the end of `async`.

old_code3 = """
                            } else {
                                completedCountAtomic.incrementAndGet()
                            }
                        }
                    }
"""

new_code3 = """
                            } else {
                                completedCountAtomic.incrementAndGet()
                            }
                            Unit
                        }
                    }
"""
content = content.replace(old_code3.strip(), new_code3.strip())

open('app/src/main/java/com/cloudvault/app/AutoBackupManager.kt', 'w').write(content)
