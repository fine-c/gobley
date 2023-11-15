// This file was autogenerated by some hot garbage in the `uniffi` crate.
// Trust me, you don't want to mess with it!

@file:Suppress("NAME_SHADOWING")

package {{ config.package_name() }}

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Callback
import com.sun.jna.Structure
import com.sun.jna.Structure.ByValue
import com.sun.jna.ptr.ByReference
import java.util.concurrent.ConcurrentHashMap
import okio.Buffer

{%- for req in self.imports() %}
{{ req.render() }}
{%- endfor %}

{% include "RustBufferTemplate.kt" %}
{% include "Helpers.kt" %}

// Contains loading, initialization code,
// and the FFI Function declarations.
{% include "NamespaceLibraryTemplate.kt" %}

// Async support
{%- if ci.has_async_fns() %}
{%- endif %}

// Public interface members begin here.
{{ type_helper_code }}

{% import "helpers.j2" as kt %}
