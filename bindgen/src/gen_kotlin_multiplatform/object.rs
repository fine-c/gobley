/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

use uniffi_bindgen::{interface::ObjectImpl, ComponentInterface};

use super::CodeType;

#[derive(Debug)]
pub struct ObjectCodeType {
    name: String,
    imp: ObjectImpl,
}

impl ObjectCodeType {
    pub fn new(name: String, imp: ObjectImpl) -> Self {
        Self { name, imp }
    }
}

impl CodeType for ObjectCodeType {
    fn type_label(&self, ci: &ComponentInterface) -> String {
        super::KotlinCodeOracle.class_name(ci, &self.name)
    }

    fn canonical_name(&self) -> String {
        format!("Type{}", self.name)
    }

    fn initialization_fn(&self) -> Option<String> {
        self.imp
            .has_callback_interface()
            .then(|| format!("uniffiCallbackInterface{}.register", self.name))
    }
}
