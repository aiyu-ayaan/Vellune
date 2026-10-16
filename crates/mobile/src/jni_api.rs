//! JNI surface for `app.vellune.core.YDoc`. A doc handle is a leaked `Box<Doc>`; the Kotlin owner
//! serializes calls per handle and frees it exactly once.

use jni::objects::{JByteArray, JClass, JObjectArray, JString};
use jni::sys::{jboolean, jbyteArray, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use yrs::Doc;

fn doc<'a>(h: jlong) -> &'a Doc {
    // SAFETY: `h` came from `nativeNew` and is only freed by `nativeFree`.
    unsafe { &*(h as *const Doc) }
}

fn bytes(env: &mut JNIEnv, v: &[u8]) -> jbyteArray {
    env.byte_array_from_slice(v).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_app_vellune_core_YDoc_nativeNew(_: JNIEnv, _: JClass) -> jlong {
    Box::into_raw(Box::new(crate::new_doc())) as jlong
}

#[no_mangle]
pub extern "system" fn Java_app_vellune_core_YDoc_nativeFree(_: JNIEnv, _: JClass, h: jlong) {
    if h != 0 {
        // SAFETY: see `doc`.
        drop(unsafe { Box::from_raw(h as *mut Doc) });
    }
}

#[no_mangle]
pub extern "system" fn Java_app_vellune_core_YDoc_nativeApplyUpdate(env: JNIEnv, _: JClass, h: jlong, update: JByteArray) -> jboolean {
    let Ok(u) = env.convert_byte_array(&update) else { return JNI_FALSE };
    if crate::apply_update(doc(h), &u).is_ok() { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_app_vellune_core_YDoc_nativeEncodeState(mut env: JNIEnv, _: JClass, h: jlong) -> jbyteArray {
    let s = crate::encode_state(doc(h));
    bytes(&mut env, &s)
}

#[no_mangle]
pub extern "system" fn Java_app_vellune_core_YDoc_nativeApplyPage(mut env: JNIEnv, _: JClass, h: jlong, json: JString) -> jbyteArray {
    let Ok(s) = env.get_string(&json) else { return std::ptr::null_mut() };
    let Ok(v) = serde_json::from_str(&String::from(s)) else { return std::ptr::null_mut() };
    let u = crate::apply_page(doc(h), &v);
    bytes(&mut env, &u)
}

#[no_mangle]
pub extern "system" fn Java_app_vellune_core_YDoc_nativeToPage(env: JNIEnv, _: JClass, h: jlong) -> jstring {
    match crate::doc_to_page(doc(h)) {
        Some(v) => env.new_string(v.to_string()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_app_vellune_core_YDoc_nativeMerge(mut env: JNIEnv, _: JClass, updates: JObjectArray) -> jbyteArray {
    let Ok(n) = env.get_array_length(&updates) else { return std::ptr::null_mut() };
    let mut all = Vec::with_capacity(n as usize);
    for i in 0..n {
        let Ok(o) = env.get_object_array_element(&updates, i) else { return std::ptr::null_mut() };
        let Ok(b) = env.convert_byte_array(JByteArray::from(o)) else { return std::ptr::null_mut() };
        all.push(b);
    }
    let refs: Vec<&[u8]> = all.iter().map(Vec::as_slice).collect();
    match crate::merge_updates(&refs) {
        Ok(m) => bytes(&mut env, &m),
        Err(_) => std::ptr::null_mut(),
    }
}
