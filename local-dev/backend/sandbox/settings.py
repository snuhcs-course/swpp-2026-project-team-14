import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
CONFIG = json.loads((ROOT / '.local/runtime.json').read_text(encoding='utf-8'))
SECRET_KEY = CONFIG['django_secret']
DEBUG = False
ALLOWED_HOSTS = ['127.0.0.1', 'localhost', '10.0.2.2', 'testserver']
INSTALLED_APPS = ['probe']
MIDDLEWARE = ['django.middleware.common.CommonMiddleware']
ROOT_URLCONF = 'sandbox.urls'
DEFAULT_AUTO_FIELD = 'django.db.models.BigAutoField'
USE_TZ = True
TIME_ZONE = 'Asia/Seoul'
DATA_UPLOAD_MAX_MEMORY_SIZE = 16 * 1024
DATABASES = {
    'default': {
        'ENGINE': 'django.db.backends.mysql',
        'NAME': 'stylemate_local',
        'USER': 'stylemate_local',
        'PASSWORD': CONFIG['db_password'],
        'HOST': '127.0.0.1',
        'PORT': CONFIG['db_port'],
        'OPTIONS': {'charset': 'utf8mb4', 'init_command': "SET sql_mode='STRICT_TRANS_TABLES'"},
        'TEST': {'NAME': 'test_stylemate_local'},
    }
}
