DISTRO=rapidoid-html/src/main/resources/public
DISTRO_JS=$DISTRO/rapidoid.min.js
DISTRO_CSS=$DISTRO/rapidoid.min.css

# JS
curl 'cdn.jsdelivr.net/g/underscorejs,jquery.cookie,jquery.easing,jquery.easy-pie-chart,jquery.validation(jquery.validate.min.js+additional-methods.min.js),jquery.parallax,jquery.prettycheckable,jquery.scrollto,jquery.timeago,angularjs(angular-sanitize.min.js+angular-resource.min.js+angular-animate.min.js+angular-cookies.min.js+angular-route.min.js+angular-loader.min.js+angular-touch.min.js),noty(packaged/jquery.noty.packaged.min.js),numeraljs,sortable,mustache.js,sweetalert,momentjs,select2,medium-editor,dropzone,typeahead.js,gmaps,sortable' | sed 's/sourceMappingURL//g' > $DISTRO_JS
cat assets/*.js >> $DISTRO_JS
echo >> $DISTRO_JS
echo >> $DISTRO_JS
cat assets-rapidoid/rapidoid-extras.js | uglifyjs >> $DISTRO_JS

# CSS
curl 'cdn.jsdelivr.net/g/sweetalert(sweetalert.css),select2(css/select2.min.css),medium-editor(css/medium-editor.min.css+css/themes/default.min.css),dropzone(dropzone.min.css)' > $DISTRO_CSS
cat assets/*.css >> $DISTRO_CSS
cat assets-rapidoid/rapidoid-extras.css >> $DISTRO_CSS

echo

ls -l $DISTRO_JS
ls -l $DISTRO_CSS

# COPY TO DOCS
DOCS=../rapidoid.github.io/
cp $DISTRO_JS $DOCS/rapidoid.min.js
cp $DISTRO_CSS $DOCS/rapidoid.min.css
cp rapidoid-html/src/main/resources/public/bootstrap/css/theme-default.css $DOCS/theme-default.css

echo
echo
