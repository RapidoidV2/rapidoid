package org.rapidoid.html.tag;

/*
 * #%L
 * rapidoid-html
 * %%
 * Copyright (C) 2014 Nikolche Mihajlovski
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import org.rapidoid.html.SpecificTag;

public interface ScriptTag extends SpecificTag<ScriptTag> {

	String src();

	ScriptTag src(String src);

	String type();

	ScriptTag type(String type);

	String charset();

	ScriptTag charset(String charset);

	boolean defer();

	ScriptTag defer(boolean defer);

	boolean async();

	ScriptTag async(boolean async);

}
