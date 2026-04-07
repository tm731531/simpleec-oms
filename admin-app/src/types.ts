export interface Merchant {
  id: string
  merchant_name: string
  merchant_email: string
  merchant_phone_number: string
  tax_id_number: string
  address_city: string
  address_region: string
  address_country: string
  address_zip: string
  address_line1: string
  address_line2: string
  address_phone_number: string
  vip_level: number
  user_local_time_zone: string
  payer_name: string
  payer_email: string
  payer_phone_number: string
  status: string
  created_at: string
  updated_at: string
}

export interface Account {
  id: string
  account_name: string
  account_email: string
  account_password?: string
  account_tel: string
  is_main_account: boolean
  access_level: number
  merchant_id: string
  status: string
  totp_secret?: string
  created_at: string
  updated_at: string
}

export interface Platform {
  id: string
  platform_name: string
  credential1?: string
  credential2?: string
  actived: boolean
  queue_topic?: string
  currency?: string
  ship_options?: Record<string, any>
  capabilities?: Record<string, any>
  created_at: string
  updated_at: string
}

export interface PageData<T> {
  total: number
  page: number
  pageSize: number
  totalPages: number
  items: T[]
}

export interface ApiResponse<T> {
  code: number
  data: T
  message: string
}

export interface PaginatedResponse<T> {
  code: number
  data: PageData<T>
  message: string
}
