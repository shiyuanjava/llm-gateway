import test from 'node:test'
import assert from 'node:assert/strict'
import { extractGatewayMessage } from './error.js'

test('prefers the management response message', () => {
  const error = {
    response: {
      data: {
        msg: '管理端消息',
        error: { message: 'OpenAI 消息' },
      },
    },
    message: '客户端消息',
  }

  assert.equal(extractGatewayMessage(error), '管理端消息')
})

test('uses the OpenAI error message when the management message is absent', () => {
  const error = {
    response: { data: { error: { message: 'OpenAI 消息' } } },
    message: '客户端消息',
  }

  assert.equal(extractGatewayMessage(error), 'OpenAI 消息')
})

test('uses the Error message when the response has no message', () => {
  assert.equal(extractGatewayMessage(new Error('客户端消息')), '客户端消息')
})

test('uses the default message when no error message is available', () => {
  assert.equal(extractGatewayMessage({}), '网络错误')
  assert.equal(extractGatewayMessage({}, '请求失败'), '请求失败')
})
