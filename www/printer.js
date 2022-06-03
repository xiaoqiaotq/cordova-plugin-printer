var exec = require('cordova/exec');

exports.coolMethod = function (arg0,arg1, success, error) {
    exec(success, error, 'PrintPlugin', 'print', [arg0,arg1]);
};
